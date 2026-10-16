package com.erp.backend.service;

import com.erp.backend.dto.creditnote.CreditNoteCreateRequest;
import com.erp.backend.dto.creditnote.CreditNoteResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
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
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S8-02: Giảm trừ công nợ khi trả hàng. */
@ExtendWith(MockitoExtension.class)
class CreditNoteServiceTest {

    @Mock private CreditNoteRepository creditNoteRepository;
    @Mock private CreditNoteSequenceRepository sequenceRepository;
    @Mock private SalesReturnRepository salesReturnRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private CreditFlagService creditFlagService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private CreditNoteService service;

    private final UserDetailsImpl accountant = actor(3, "ROLE_ACCOUNTANT");
    private Customer c;
    private Invoice hd1;
    private InvoiceLine il;
    private SalesReturn ret;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(LocalDate.of(2026, 10, 16).atStartOfDay(CreditNoteService.VN_ZONE).plusHours(9)
                .toInstant(), CreditNoteService.VN_ZONE);
        c = customer(6, null);
        hd1 = invoice(1, "1000000", "1000000");
        // Dòng hoá đơn: 10 thùng, giá 100.000, chiết khấu 10% -> thành tiền 900.000 (giá đã bán = 90.000 / thùng)
        il = InvoiceLine.builder().id(11L).invoice(hd1).productSku("SP-X").productName("Bia X").unitName("Thùng")
                .quantity(new BigDecimal("10")).pricePerUnit(new BigDecimal("100000"))
                .grossAmount(new BigDecimal("1000000")).discountAmount(new BigDecimal("100000"))
                .netAmount(new BigDecimal("900000")).build();
        ret = SalesReturn.builder().id(5L).code("TH2026-0001").status(SalesReturn.STATUS_RECEIVED).invoice(hd1)
                .invoiceCode(hd1.getCode()).customer(c).returnReason("DAMAGED").build();
        ret.addLine(SalesReturnLine.builder().id(51L).invoiceLine(il).returnQuantity(new BigDecimal("3")).build());
    }

    private Invoice invoice(long id, String total, String remaining) {
        Invoice inv = Invoice.builder().id(id).code("HD2026-00000" + id).customer(c)
                .invoiceDate(LocalDate.of(2026, 9, (int) id)).totalAmount(new BigDecimal(total)).build();
        inv.setRemainingAmount(new BigDecimal(remaining));
        return inv;
    }

    private void common(List<Invoice> unpaid, List<Invoice> locked) {
        when(salesReturnRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(ret));
        when(creditNoteRepository.existsBySalesReturn_Id(5L)).thenReturn(false);
        when(customerRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(c));
        when(creditNoteRepository.sumCreditedQuantityByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(creditNoteRepository.sumCreditedAmountByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(6L)).thenReturn(unpaid);
        when(invoiceRepository.findAllByIdForUpdate(anyCollection())).thenReturn(locked);
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(new CreditNoteSequence(2026, 4L)));
        when(creditNoteRepository.save(any(CreditNote.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    @DisplayName("S8-02: Giảm trừ theo giá đã bán trên hoá đơn gốc (sau chiết khấu), không phải giá niêm yết")
    void creditAtOriginalNetPrice() {
        common(List.of(hd1), List.of(hd1));

        CreditNoteResponse r = service.createFromReturn(new CreditNoteCreateRequest(5L, "Hàng vỡ"), accountant);

        // 3 / 10 thùng x 900.000 = 270.000 (không phải 3 x 100.000 = 300.000)
        assertThat(r.amount()).isEqualByComparingTo("270000");
        assertThat(r.lines()).hasSize(1);
        assertThat(r.lines().get(0).invoiceNetAmount()).isEqualByComparingTo("900000");
        assertThat(r.code()).isEqualTo("GG2026-000005");
        assertThat(r.salesReturnCode()).isEqualTo("TH2026-0001");
        assertThat(r.invoiceCode()).isEqualTo("HD2026-000001");
    }

    @Test
    @DisplayName("S8-02: Công nợ giảm ngay trên hoá đơn gốc; hoá đơn gốc giữ nguyên tổng tiền và dòng hàng")
    void offsetsOriginalInvoice_keepsInvoiceTotal() {
        common(List.of(hd1), List.of(hd1));

        CreditNoteResponse r = service.createFromReturn(new CreditNoteCreateRequest(5L, null), accountant);

        assertThat(hd1.getRemainingAmount()).isEqualByComparingTo("730000");
        assertThat(hd1.getTotalAmount()).isEqualByComparingTo("1000000");
        assertThat(il.getNetAmount()).isEqualByComparingTo("900000");
        assertThat(il.getQuantity()).isEqualByComparingTo("10");
        assertThat(hd1.getPaymentStatus()).isEqualTo(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);
        assertThat(r.allocations()).hasSize(1);
        assertThat(r.allocations().get(0).remainingBefore()).isEqualByComparingTo("1000000");
        assertThat(r.allocations().get(0).remainingAfter()).isEqualByComparingTo("730000");
        assertThat(r.appliedAmount()).isEqualByComparingTo("270000");
        assertThat(r.unappliedAmount()).isEqualByComparingTo("0");
        verify(invoiceRepository).save(hd1);
        verify(creditFlagService).reevaluate(6L, "CREDIT_NOTE");
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("CREDIT_NOTE_OFFSET"), eq("INVOICE"), eq(1L),
                eq("HD2026-000001"), eq("1000000.00"), eq("730000.00"), anyString(), eq(accountant));
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("CREATE_CREDIT_NOTE"), any(), any(), any(), any(),
                any(), anyString(), eq(accountant));
    }

    @Test
    @DisplayName("S8-02: Hoá đơn gốc đã trả hết -> đối trừ sang hoá đơn còn nợ cũ nhất")
    void originalPaid_offsetsOldestOpenInvoice() {
        hd1.setRemainingAmount(BigDecimal.ZERO);
        Invoice hd2 = invoice(2, "500000", "500000");
        common(List.of(hd2), List.of(hd1, hd2));

        CreditNoteResponse r = service.createFromReturn(new CreditNoteCreateRequest(5L, null), accountant);

        assertThat(hd1.getRemainingAmount()).isEqualByComparingTo("0");
        assertThat(hd2.getRemainingAmount()).isEqualByComparingTo("230000");
        assertThat(r.allocations()).extracting(a -> a.invoiceCode()).containsExactly("HD2026-000002");
        assertThat(r.unappliedAmount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("S8-02: Không còn hoá đơn nợ -> toàn bộ là số dư có của đại lý (trừ vào công nợ)")
    void nothingOpen_unappliedCredit() {
        hd1.setRemainingAmount(BigDecimal.ZERO);
        common(List.of(), List.of(hd1));

        CreditNoteResponse r = service.createFromReturn(new CreditNoteCreateRequest(5L, null), accountant);

        assertThat(r.allocations()).isEmpty();
        assertThat(r.appliedAmount()).isEqualByComparingTo("0");
        assertThat(r.unappliedAmount()).isEqualByComparingTo("270000");
        verify(invoiceRepository, never()).save(any(Invoice.class));
    }

    @Test
    @DisplayName("S8-02: Phiếu trả chưa được thủ kho xác nhận -> chưa lập được chứng từ")
    void returnNotReceived_conflict() {
        ret.setStatus(SalesReturn.STATUS_PENDING_RECEIPT);
        when(salesReturnRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(ret));

        assertThatThrownBy(() -> service.createFromReturn(new CreditNoteCreateRequest(5L, null), accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "SALES_RETURN_NOT_RECEIVED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    @DisplayName("S8-02: Mỗi phiếu trả chỉ lập 1 chứng từ giảm trừ")
    void duplicate_conflict() {
        when(salesReturnRepository.findByIdForUpdate(5L)).thenReturn(Optional.of(ret));
        when(creditNoteRepository.existsBySalesReturn_Id(5L)).thenReturn(true);

        assertThatThrownBy(() -> service.createFromReturn(new CreditNoteCreateRequest(5L, null), accountant))
                .hasFieldOrPropertyWithValue("code", "CREDIT_NOTE_EXISTS");
        verifyNoInteractions(invoiceRepository);
        verify(creditNoteRepository, never()).save(any());
    }

    @Test
    @DisplayName("S8-02: Trả nhiều lần cùng 1 dòng: lần cuối lấy đúng phần tiền còn lại, tổng không lệch 1 đồng")
    void lineCredit_rounding() {
        BigDecimal qty = new BigDecimal("3");
        BigDecimal net = new BigDecimal("1000000");
        BigDecimal first = CreditNoteService.lineCredit(qty, net, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        BigDecimal second = CreditNoteService.lineCredit(qty, net, BigDecimal.ONE, first, BigDecimal.ONE);
        BigDecimal third = CreditNoteService.lineCredit(qty, net, new BigDecimal("2"), first.add(second), BigDecimal.ONE);

        assertThat(first).isEqualByComparingTo("333333.33");
        assertThat(second).isEqualByComparingTo("333333.33");
        assertThat(third).isEqualByComparingTo("333333.34");
        assertThat(first.add(second).add(third)).isEqualByComparingTo(net);
    }

    @Test
    @DisplayName("S8-02: Không giảm trừ vượt phần tiền còn lại của dòng hoá đơn")
    void lineCredit_cappedAtRemaining() {
        BigDecimal r = CreditNoteService.lineCredit(new BigDecimal("10"), new BigDecimal("100000"),
                new BigDecimal("5"), new BigDecimal("95000"), new BigDecimal("4"));

        assertThat(r).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("S8-02: NV kinh doanh không xem được danh sách phiếu trả chờ giảm trừ")
    void pendingReturns_forbiddenForSalesRep() {
        assertThatThrownBy(() -> service.pendingReturns(actor(7, "ROLE_SALES_REP")))
                .hasFieldOrPropertyWithValue("status", HttpStatus.FORBIDDEN);
        verifyNoInteractions(creditNoteRepository);
    }

    @Test
    @DisplayName("S8-02: Phiếu trả chờ giảm trừ kèm số tiền sẽ giảm trừ")
    void pendingReturns_ok() {
        when(creditNoteRepository.findReceivedReturnsWithoutCreditNote()).thenReturn(List.of(ret));
        when(creditNoteRepository.sumCreditedQuantityByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);
        when(creditNoteRepository.sumCreditedAmountByInvoiceLine(11L)).thenReturn(BigDecimal.ZERO);

        var list = service.pendingReturns(accountant);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).salesReturnCode()).isEqualTo("TH2026-0001");
        assertThat(list.get(0).creditAmount()).isEqualByComparingTo("270000");
    }

    @Test
    @DisplayName("S8-02: Đại lý xem chứng từ của đại lý khác -> 404")
    void get_otherCustomer_hidden() {
        CreditNote cn = CreditNote.builder().id(9L).customer(c).build();
        when(creditNoteRepository.findById(9L)).thenReturn(Optional.of(cn));

        assertThatThrownBy(() -> service.get(9L, actor(50, "ROLE_CUSTOMER")))
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }
}
