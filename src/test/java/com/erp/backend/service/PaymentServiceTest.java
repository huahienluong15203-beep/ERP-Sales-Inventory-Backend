package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentAllocationItemRequest;
import com.erp.backend.dto.payment.PaymentReceiptCreateRequest;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S7-02: Kiểm thử tính năng ghi nhận thanh toán và cấn trừ công nợ đại lý.
 * - SCRUM-234: Test BE - Test cấn trừ tự động hoá đơn cũ nhất.
 * - SCRUM-259: Viết Thuật toán cấn trừ công nợ (FIFO/Chỉ định).
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private PaymentAllocationRepository paymentAllocationRepository;
    @Mock private PaymentSequenceRepository sequenceRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private UserRepository userRepository;
    @Mock private CreditFlagService creditFlagService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PaymentService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final UserDetailsImpl accountant = actor(50, "ROLE_ACCOUNTANT");
    private Customer cust;
    private Invoice inv1;
    private Invoice inv2;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(VN).toInstant(), VN);

        cust = customer(6L, null);
        cust.setName("Đại lý Minh Phát");
        cust.setMaxDebtDays(30);

        // HĐ 1 phát hành ngày 2026-10-01 (cũ hơn): 50.000.000đ
        inv1 = Invoice.builder()
                .id(101L)
                .code("HD2026-000001")
                .seqYear(2026)
                .seqNumber(1L)
                .invoiceDate(LocalDate.of(2026, 10, 1))
                .customer(cust)
                .customerCode(cust.getCode())
                .customerName(cust.getName())
                .totalAmount(new BigDecimal("50000000.00"))
                .paidAmount(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("50000000.00"))
                .paymentStatus(Invoice.PAYMENT_STATUS_UNPAID)
                .status(Invoice.STATUS_ISSUED)
                .lines(new ArrayList<>())
                .build();

        // HĐ 2 phát hành ngày 2026-10-10: 30.000.000đ
        inv2 = Invoice.builder()
                .id(102L)
                .code("HD2026-000002")
                .seqYear(2026)
                .seqNumber(2L)
                .invoiceDate(LocalDate.of(2026, 10, 10))
                .customer(cust)
                .customerCode(cust.getCode())
                .customerName(cust.getName())
                .totalAmount(new BigDecimal("30000000.00"))
                .paidAmount(BigDecimal.ZERO)
                .remainingAmount(new BigDecimal("30000000.00"))
                .paymentStatus(Invoice.PAYMENT_STATUS_UNPAID)
                .status(Invoice.STATUS_ISSUED)
                .lines(new ArrayList<>())
                .build();
    }

    @Test
    @DisplayName("SCRUM-234: FIFO cấn trừ hoá đơn cũ nhất trước - HĐ1 trả đủ (PAID), HĐ2 trả một phần (PARTIALLY_PAID)")
    void fifoAllocation_success_fullAndPartialPayment() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(10L).build()));
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(6L)).thenReturn(List.of(inv1, inv2));
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> {
            PaymentReceipt r = i.getArgument(0);
            r.setId(1L);
            return r;
        });

        // Khách trả 65.000.000đ (50tr cho HĐ1, 15tr cho HĐ2)
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("65000000.00"))
                .paymentMethod("BANK_TRANSFER")
                .paymentDate(TODAY)
                .collectorName("Kế toán thu tiền")
                .notes("Thu tiền qua chuyển khoản")
                .allocationType("FIFO")
                .build();

        PaymentReceiptResponse response = service.createReceipt(request, accountant);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo("PT2026-000011");
        assertThat(response.amount()).isEqualByComparingTo("65000000.00");
        assertThat(response.status()).isEqualTo("CONFIRMED");

        // HĐ 1 phải trả đủ 50tr -> PAID
        assertThat(inv1.getPaidAmount()).isEqualByComparingTo("50000000.00");
        assertThat(inv1.getRemainingAmount()).isEqualByComparingTo("0.00");
        assertThat(inv1.getPaymentStatus()).isEqualTo(Invoice.PAYMENT_STATUS_PAID);

        // HĐ 2 được cấn trừ 15tr -> còn nợ 15tr -> PARTIALLY_PAID (AC3)
        assertThat(inv2.getPaidAmount()).isEqualByComparingTo("15000000.00");
        assertThat(inv2.getRemainingAmount()).isEqualByComparingTo("15000000.00");
        assertThat(inv2.getPaymentStatus()).isEqualTo(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);

        // Kiểm tra phân bổ chi tiết
        assertThat(response.allocations()).hasSize(2);
        assertThat(response.allocations().get(0).invoiceCode()).isEqualTo("HD2026-000001");
        assertThat(response.allocations().get(0).allocatedAmount()).isEqualByComparingTo("50000000.00");
        assertThat(response.allocations().get(0).remainingAfter()).isEqualByComparingTo("0.00");

        assertThat(response.allocations().get(1).invoiceCode()).isEqualTo("HD2026-000002");
        assertThat(response.allocations().get(1).allocatedAmount()).isEqualByComparingTo("15000000.00");
        assertThat(response.allocations().get(1).remainingAfter()).isEqualByComparingTo("15000000.00");

        // Tự động đánh giá lại cờ công nợ
        verify(creditFlagService).reevaluate(6L, "PAYMENT");

        // Ghi nhật ký kiểm toán
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("CREATE_PAYMENT_RECEIPT"),
                eq("PAYMENT_RECEIPT"), anyLong(), eq("PT2026-000011"), isNull(), anyString(), anyString(), eq(accountant));
    }

    @Test
    @DisplayName("AC4: FIFO - Không cho thu vượt tổng công nợ còn lại của đại lý")
    void fifoAllocation_failsWhenAmountExceedsTotalDebt() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(1L).build()));
        // Tổng nợ = 50tr + 30tr = 80tr
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(6L)).thenReturn(List.of(inv1, inv2));

        // Khách thu 90.000.000đ (vượt 80.000.000đ)
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("90000000.00"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .collectorName("Kế toán thu tiền")
                .allocationType("FIFO")
                .build();

        assertThatThrownBy(() -> service.createReceipt(request, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("AMOUNT_EXCEEDS_TOTAL_DEBT");
                    assertThat(be.getMessage()).contains("vượt quá tổng công nợ còn lại");
                });

        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("AC2 & AC3: Cấn trừ chỉ định vào hoá đơn cụ thể")
    void specificAllocation_success() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(5L).build()));
        when(invoiceRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(inv2));
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> {
            PaymentReceipt r = i.getArgument(0);
            r.setId(2L);
            return r;
        });

        // Chỉ định cấn trừ 20tr vào riêng HĐ 2 (nợ 30tr)
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("20000000.00"))
                .paymentMethod("BANK_TRANSFER")
                .paymentDate(TODAY)
                .collectorName("Kế toán")
                .allocationType("SPECIFIC")
                .specificAllocations(List.of(
                        PaymentAllocationItemRequest.builder()
                                .invoiceId(102L)
                                .amount(new BigDecimal("20000000.00"))
                                .build()
                ))
                .build();

        PaymentReceiptResponse response = service.createReceipt(request, accountant);

        assertThat(response).isNotNull();
        assertThat(response.code()).isEqualTo("PT2026-000006");

        // HĐ 1 giữ nguyên
        assertThat(inv1.getPaidAmount()).isEqualByComparingTo("0.00");
        assertThat(inv1.getRemainingAmount()).isEqualByComparingTo("50000000.00");

        // HĐ 2 được cấn trừ 20tr -> còn 10tr -> PARTIALLY_PAID
        assertThat(inv2.getPaidAmount()).isEqualByComparingTo("20000000.00");
        assertThat(inv2.getRemainingAmount()).isEqualByComparingTo("10000000.00");
        assertThat(inv2.getPaymentStatus()).isEqualTo(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);

        assertThat(response.allocations()).hasSize(1);
        assertThat(response.allocations().get(0).invoiceCode()).isEqualTo("HD2026-000002");
        assertThat(response.allocations().get(0).allocatedAmount()).isEqualByComparingTo("20000000.00");
    }

    @Test
    @DisplayName("AC4: Chỉ định - Không cho cấn trừ vượt số nợ còn lại của hoá đơn chỉ định")
    void specificAllocation_failsWhenExceedsInvoiceRemaining() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(1L).build()));
        when(invoiceRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(inv2));

        // HĐ 2 nợ 30tr, cố tình phân bổ 35tr
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("35000000.00"))
                .paymentMethod("BANK_TRANSFER")
                .paymentDate(TODAY)
                .collectorName("Kế toán")
                .allocationType("SPECIFIC")
                .specificAllocations(List.of(
                        PaymentAllocationItemRequest.builder()
                                .invoiceId(102L)
                                .amount(new BigDecimal("35000000.00"))
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> service.createReceipt(request, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("AMOUNT_EXCEEDS_INVOICE_DEBT");
                    assertThat(be.getMessage()).contains("vượt quá số nợ còn lại");
                });
    }

    @Test
    @DisplayName("Chỉ định - Báo lỗi khi tổng tiền phân bổ không khớp với số tiền thu")
    void specificAllocation_failsWhenTotalMismatch() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(1L).build()));

        // Thu 50tr nhưng phân bổ 40tr
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("50000000.00"))
                .paymentMethod("BANK_TRANSFER")
                .paymentDate(TODAY)
                .collectorName("Kế toán")
                .allocationType("SPECIFIC")
                .specificAllocations(List.of(
                        PaymentAllocationItemRequest.builder()
                                .invoiceId(101L)
                                .amount(new BigDecimal("40000000.00"))
                                .build()
                ))
                .build();

        assertThatThrownBy(() -> service.createReceipt(request, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("ALLOCATION_AMOUNT_MISMATCH");
                    assertThat(be.getMessage()).contains("phải bằng đúng số tiền thu");
                });
    }

    @Test
    @DisplayName("AC1: Chặn ngày thu tiền ở tương lai")
    void paymentDate_future_throwsBadRequest() {
        PaymentReceiptCreateRequest request = PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("10000000.00"))
                .paymentMethod("CASH")
                .paymentDate(TODAY.plusDays(1)) // ngày mai
                .collectorName("Kế toán")
                .build();

        assertThatThrownBy(() -> service.createReceipt(request, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("PAYMENT_DATE_FUTURE");
                });
    }

    @Test
    @DisplayName("AC5: Đã ghi nhận thì không xoá - @PreRemove ném IllegalStateException")
    void preventDelete_throwsIllegalStateException() {
        PaymentReceipt receipt = PaymentReceipt.builder()
                .id(1L)
                .code("PT2026-000001")
                .amount(new BigDecimal("10000000.00"))
                .build();

        assertThatThrownBy(receipt::preventDelete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không được xoá, chỉ lập bút toán điều chỉnh");

        PaymentAllocation alloc = PaymentAllocation.builder()
                .id(1L)
                .allocatedAmount(new BigDecimal("10000000.00"))
                .build();

        assertThatThrownBy(alloc::preventDelete)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("không được xoá, chỉ lập bút toán điều chỉnh");
    }

    // ======================= SỬA LỖI TEST BE (TC702-12, TC702-14, TC702-20) =======================

    private PaymentReceiptCreateRequest.PaymentReceiptCreateRequestBuilder baseRequest() {
        return PaymentReceiptCreateRequest.builder()
                .customerId(6L)
                .amount(new BigDecimal("1000000.00"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .collectorName("Kế toán");
    }

    @Test
    @DisplayName("TC702-12: Hình thức thu / kiểu đối trừ không hợp lệ -> 400, không tạo phiếu")
    void invalidMethodOrAllocationType_rejected() {
        assertThatThrownBy(() -> service.createReceipt(baseRequest().paymentMethod("ABC").build(), accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PAYMENT_METHOD");
        assertThatThrownBy(() -> service.createReceipt(baseRequest().allocationType("XYZ").build(), accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ALLOCATION_TYPE");
        verifyNoInteractions(customerRepository, paymentReceiptRepository);
    }

    @Test
    @DisplayName("TC702-12: Hình thức thu viết thường / MANUAL được chuẩn hoá")
    void normalizeValues() {
        assertThat(PaymentService.normalizePaymentMethod(" bank_transfer ")).isEqualTo("BANK_TRANSFER");
        assertThat(PaymentService.normalizePaymentMethod(null)).isEqualTo("CASH");
        assertThat(PaymentService.normalizeAllocationType("manual")).isEqualTo("SPECIFIC");
        assertThat(PaymentService.normalizeAllocationType(null)).isEqualTo("FIFO");
    }

    @Test
    @DisplayName("TC702-14: NV kinh doanh không ghi nhận thu trừ nợ ngay được (409, phải qua thu tiền theo tuyến)")
    void salesRep_cannotCreateConfirmedReceipt() {
        UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");

        assertThatThrownBy(() -> service.createReceipt(baseRequest().build(), rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "PAYMENT_ACCOUNTANT_ONLY")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verifyNoInteractions(customerRepository, invoiceRepository, paymentReceiptRepository);
    }

    @Test
    @DisplayName("TC702-20: Cùng 1 hoá đơn chọn 2 lần trong đối trừ chỉ định -> 400, không trừ nợ")
    void specificAllocation_duplicateInvoice_rejected() {
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(1L).build()));
        PaymentReceiptCreateRequest request = baseRequest()
                .allocationType("SPECIFIC")
                .specificAllocations(List.of(
                        PaymentAllocationItemRequest.builder().invoiceId(101L).amount(new BigDecimal("500000.00")).build(),
                        PaymentAllocationItemRequest.builder().invoiceId(101L).amount(new BigDecimal("500000.00")).build()))
                .build();

        assertThatThrownBy(() -> service.createReceipt(request, accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_ALLOCATION_INVOICE");
        verify(invoiceRepository, never()).findAllByIdForUpdate(any());
        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("TC-COLLECTORS: Lấy danh sách nhân sự thu tiền / lập phiếu hợp lệ")
    void getEligibleCollectors_success() {
        Role roleAccountant = Role.builder().name(RoleName.ROLE_ACCOUNTANT).build();
        User u1 = User.builder()
                .id(10L)
                .username("ketoan1")
                .fullName("Nguyễn Văn Kế Toán")
                .phone("0901234567")
                .roles(java.util.Set.of(roleAccountant))
                .status("ACTIVE")
                .build();

        when(userRepository.findDistinctByRoles_NameInAndStatusOrderByFullNameAsc(anyCollection(), eq("ACTIVE")))
                .thenReturn(List.of(u1));

        var collectors = service.getEligibleCollectors();

        assertThat(collectors).hasSize(1);
        assertThat(collectors.get(0).getUsername()).isEqualTo("ketoan1");
        assertThat(collectors.get(0).getFullName()).isEqualTo("Nguyễn Văn Kế Toán");
        assertThat(collectors.get(0).getRole()).isEqualTo("ROLE_ACCOUNTANT");
    }
}
