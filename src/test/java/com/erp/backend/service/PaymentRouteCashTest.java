package com.erp.backend.service;

import com.erp.backend.dto.payment.PaymentReconciliationResponse;
import com.erp.backend.dto.payment.PaymentReceiptResponse;
import com.erp.backend.dto.payment.PaymentRejectRequest;
import com.erp.backend.dto.payment.RouteCashCollectionRequest;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.PaymentReceipt;
import com.erp.backend.entity.PaymentSequence;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.repository.PaymentReceiptRepository;
import com.erp.backend.repository.PaymentSequenceRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * S7-05: Thu tiền theo tuyến và nộp về công ty, đối chiếu tiền đã thu với tiền đã nộp.
 * - SCRUM-261: API nộp/duyệt quỹ, lưu vết ảnh biên lai.
 * - SCRUM-204: Test BE: Test luồng xác nhận chờ nộp.
 */
@ExtendWith(MockitoExtension.class)
class PaymentRouteCashTest {

    @Mock private PaymentReceiptRepository paymentReceiptRepository;
    @Mock private PaymentSequenceRepository sequenceRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CreditFlagService creditFlagService;
    @Mock private AuditLogService auditLogService;
    @Mock private ReceiptImageStorageService receiptImageStorage;

    @InjectMocks private PaymentService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    private final UserDetailsImpl salesRep = actor(25, "ROLE_SALES_REP");
    private final UserDetailsImpl accountant = actor(40, "ROLE_ACCOUNTANT");

    private Customer cust;
    private Invoice inv;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(VN).toInstant(), VN);

        cust = customer(8L, salesRep(25L));
        cust.setName("Đại lý Thành Công");

        inv = Invoice.builder()
                .id(201L)
                .code("HD2026-000201")
                .seqYear(2026)
                .seqNumber(201L)
                .invoiceDate(LocalDate.of(2026, 10, 5))
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
    @DisplayName("SCRUM-261: NVKD ghi nhận thu tiền tại tuyến -> trạng thái Chờ nộp (PENDING_SUBMISSION), lưu vết ảnh biên lai")
    void submitRouteCash_success_pendingSubmission() {
        when(customerRepository.findById(8L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(5L).build()));
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> {
            PaymentReceipt r = i.getArgument(0);
            r.setId(10L);
            return r;
        });

        RouteCashCollectionRequest req = RouteCashCollectionRequest.builder()
                .customerId(8L)
                .amount(new BigDecimal("15000000.00"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .notes("Thu tiền mặt tại cửa hàng")
                .receiptImageUrl("https://erp-storage.com/receipts/rc_101.jpg")
                .build();

        PaymentReceiptResponse res = service.submitRouteCash(req, salesRep);

        assertThat(res).isNotNull();
        assertThat(res.code()).isEqualTo("PT2026-000006");
        assertThat(res.amount()).isEqualByComparingTo("15000000.00");
        assertThat(res.status()).isEqualTo("PENDING_SUBMISSION");
        assertThat(res.receiptImageUrl()).isEqualTo("https://erp-storage.com/receipts/rc_101.jpg");
        // Chưa cấn trừ hoá đơn nợ khi đang ở trạng thái Chờ nộp
        assertThat(res.allocations()).isEmpty();
    }

    @Test
    @DisplayName("S7-05: Thu tiền tại tuyến không kèm ảnh biên lai -> 400 RECEIPT_IMAGE_REQUIRED, không cấp số phiếu")
    void submitRouteCash_withoutImage_rejected() {
        RouteCashCollectionRequest req = RouteCashCollectionRequest.builder()
                .customerId(8L)
                .amount(new BigDecimal("100000"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .build();

        assertThatThrownBy(() -> service.submitRouteCash(req, salesRep))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("RECEIPT_IMAGE_REQUIRED");
                });
        verify(sequenceRepository, never()).findForUpdate(anyInt());
        verify(paymentReceiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("S7-05: Ảnh base64 được lưu thành tệp, phiếu thu chỉ giữ tên tệp và trả đường dẫn API xem ảnh")
    void submitRouteCash_base64Image_storedAsFile() {
        when(customerRepository.findById(8L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(PaymentSequence.builder().seqYear(2026).lastNumber(0L).build()));
        when(receiptImageStorage.storeDataUrl("data:image/jpeg;base64,/9j/AAAA")).thenReturn("rc_1_abcd1234.jpg");
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> {
            PaymentReceipt r = i.getArgument(0);
            r.setId(11L);
            return r;
        });

        RouteCashCollectionRequest req = RouteCashCollectionRequest.builder()
                .customerId(8L)
                .amount(new BigDecimal("100000"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .receiptImageBase64("data:image/jpeg;base64,/9j/AAAA")
                .build();

        PaymentReceiptResponse res = service.submitRouteCash(req, salesRep);

        verify(paymentReceiptRepository).save(argThat(r -> "rc_1_abcd1234.jpg".equals(r.getReceiptImageUrl())));
        assertThat(res.receiptImageUrl()).isEqualTo("/api/payments/11/image");
    }

    @Test
    @DisplayName("S7-05: Dữ liệu ảnh cũ không hợp lệ (không phải tệp hệ thống, không phải http) -> không trả ra ngoài")
    void receiptImageUrl_legacyInvalidValue_hidden() {
        PaymentReceipt r = PaymentReceipt.builder().id(12L).receiptImageUrl("data:image/png;base64,iVBORw0KGgo").build();
        assertThat(PaymentService.receiptImageUrl(r)).isNull();
        r.setReceiptImageUrl("https://cdn.example.com/a.jpg");
        assertThat(PaymentService.receiptImageUrl(r)).isEqualTo("https://cdn.example.com/a.jpg");
        r.setReceiptImageUrl("rc_1_abcd1234.png");
        assertThat(PaymentService.receiptImageUrl(r)).isEqualTo("/api/payments/12/image");
    }

    @Test
    @DisplayName("S7-05: Xem ảnh biên lai của phiếu không có ảnh lưu trên hệ thống -> 404")
    void getReceiptImage_noStoredFile_notFound() {
        PaymentReceipt r = PaymentReceipt.builder().id(13L).code("PT2026-000013").customer(cust)
                .receiptImageUrl("https://cdn.example.com/a.jpg").build();
        when(paymentReceiptRepository.findById(13L)).thenReturn(Optional.of(r));

        assertThatThrownBy(() -> service.getReceiptImage(13L, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
        verifyNoInteractions(receiptImageStorage);
    }

    @Test
    @DisplayName("SCRUM-204: Kế toán xác nhận nhận tiền nộp quỹ -> chuyển sang CONFIRMED và cấn trừ hoá đơn theo FIFO")
    void confirmRouteCash_success_fifoAllocation() {
        PaymentReceipt pending = PaymentReceipt.builder()
                .id(10L)
                .code("PT2026-000006")
                .customer(cust)
                .customerCode(cust.getCode())
                .customerName(cust.getName())
                .amount(new BigDecimal("15000000.00"))
                .paymentMethod("CASH")
                .paymentDate(TODAY)
                .collectorName("Nhân viên bán hàng")
                .collectorUsername("sales_rep")
                .collectorId(25L)
                .status(PaymentReceipt.STATUS_PENDING_SUBMISSION)
                .allocations(new ArrayList<>())
                .build();

        when(paymentReceiptRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(8L)).thenReturn(List.of(inv));
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> i.getArgument(0));

        PaymentReceiptResponse res = service.confirmRouteCash(10L, accountant);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo("CONFIRMED");
        assertThat(res.confirmedByUsername()).isEqualTo("user40");

        // Hoá đơn 30tr được cấn trừ 15tr -> còn 15tr -> PARTIALLY_PAID
        assertThat(inv.getPaidAmount()).isEqualByComparingTo("15000000.00");
        assertThat(inv.getRemainingAmount()).isEqualByComparingTo("15000000.00");
        assertThat(inv.getPaymentStatus()).isEqualTo(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);

        assertThat(res.allocations()).hasSize(1);
        assertThat(res.allocations().get(0).allocatedAmount()).isEqualByComparingTo("15000000.00");

        verify(creditFlagService).reevaluate(8L, "PAYMENT");
    }

    @Test
    @DisplayName("SCRUM-204: Xác nhận thất bại nếu khoản thu không ở trạng thái Chờ nộp (ví dụ đã CONFIRMED)")
    void confirmRouteCash_failsWhenNotPending() {
        PaymentReceipt alreadyConfirmed = PaymentReceipt.builder()
                .id(10L)
                .code("PT2026-000006")
                .status(PaymentReceipt.STATUS_CONFIRMED)
                .build();

        when(paymentReceiptRepository.findById(10L)).thenReturn(Optional.of(alreadyConfirmed));

        assertThatThrownBy(() -> service.confirmRouteCash(10L, accountant))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(be.getCode()).isEqualTo("INVALID_STATUS");
                });

        verify(invoiceRepository, never()).findUnpaidByCustomerIdOrderByInvoiceDateAsc(any());
    }

    @Test
    @DisplayName("Kế toán từ chối nhận tiền nộp quỹ -> chuyển sang REJECTED kèm lý do")
    void rejectRouteCash_success() {
        PaymentReceipt pending = PaymentReceipt.builder()
                .id(10L)
                .code("PT2026-000006")
                .customer(cust)
                .amount(new BigDecimal("15000000.00"))
                .status(PaymentReceipt.STATUS_PENDING_SUBMISSION)
                .build();

        when(paymentReceiptRepository.findById(10L)).thenReturn(Optional.of(pending));
        when(paymentReceiptRepository.save(any(PaymentReceipt.class))).thenAnswer(i -> i.getArgument(0));

        PaymentRejectRequest req = PaymentRejectRequest.builder()
                .reason("Tiền mặt nộp thiếu so với biên lai")
                .build();

        PaymentReceiptResponse res = service.rejectRouteCash(10L, req, accountant);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo("REJECTED");
        assertThat(res.rejectionReason()).isEqualTo("Tiền mặt nộp thiếu so với biên lai");
        assertThat(res.rejectedByUsername()).isEqualTo("user40");

        // Không cấn trừ hoá đơn
        verify(invoiceRepository, never()).findUnpaidByCustomerIdOrderByInvoiceDateAsc(any());
    }

    @Test
    @DisplayName("S7-05: Bảng đối chiếu tiền thu tính toán đúng tổng thu, chờ nộp, đã xác nhận và từ chối")
    void getReconciliation_calculatesCorrectTotals() {
        PaymentReceipt r1 = PaymentReceipt.builder()
                .id(1L)
                .code("PT2026-000001")
                .customer(cust)
                .collectorId(25L)
                .collectorName("Nhân viên bán hàng")
                .amount(new BigDecimal("20000000.00"))
                .status(PaymentReceipt.STATUS_CONFIRMED)
                .createdAt(LocalDateTime.now())
                .paymentDate(TODAY)
                .allocations(new ArrayList<>())
                .build();

        PaymentReceipt r2 = PaymentReceipt.builder()
                .id(2L)
                .code("PT2026-000002")
                .customer(cust)
                .collectorId(25L)
                .collectorName("Nhân viên bán hàng")
                .amount(new BigDecimal("15000000.00"))
                .status(PaymentReceipt.STATUS_PENDING_SUBMISSION)
                .createdAt(LocalDateTime.now())
                .paymentDate(TODAY)
                .allocations(new ArrayList<>())
                .build();

        PaymentReceipt r3 = PaymentReceipt.builder()
                .id(3L)
                .code("PT2026-000003")
                .customer(cust)
                .collectorId(25L)
                .collectorName("Nhân viên bán hàng")
                .amount(new BigDecimal("5000000.00"))
                .status(PaymentReceipt.STATUS_REJECTED)
                .rejectionReason("Sai biên lai")
                .createdAt(LocalDateTime.now())
                .paymentDate(TODAY)
                .allocations(new ArrayList<>())
                .build();

        when(paymentReceiptRepository.findForReconciliation(25L, TODAY, TODAY))
                .thenReturn(List.of(r1, r2, r3));

        PaymentReconciliationResponse recon = service.getReconciliation(TODAY, TODAY, 25L, accountant);

        assertThat(recon).isNotNull();
        assertThat(recon.totalCollectedAmount()).isEqualByComparingTo("40000000.00");
        assertThat(recon.confirmedAmount()).isEqualByComparingTo("20000000.00");
        assertThat(recon.pendingAmount()).isEqualByComparingTo("15000000.00");
        assertThat(recon.rejectedAmount()).isEqualByComparingTo("5000000.00");
        assertThat(recon.totalCount()).isEqualTo(3);
        assertThat(recon.confirmedCount()).isEqualTo(1);
        assertThat(recon.pendingCount()).isEqualTo(1);
        assertThat(recon.rejectedCount()).isEqualTo(1);
        assertThat(recon.items()).hasSize(3);
    }
}
