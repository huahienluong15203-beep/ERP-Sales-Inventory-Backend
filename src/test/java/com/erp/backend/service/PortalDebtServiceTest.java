package com.erp.backend.service;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.payment.CustomerInvoiceDebtResponse;
import com.erp.backend.dto.portal.PortalDebtSummaryResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Invoice;
import com.erp.backend.entity.InvoiceLine;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.InvoiceRepository;
import com.erp.backend.security.UserDetailsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** S7-07: Đại lý tự xem công nợ. */
@ExtendWith(MockitoExtension.class)
class PortalDebtServiceTest {

    @Mock private CustomerRepository customerRepository;
    @Mock private InvoiceRepository invoiceRepository;
    @Mock private CustomerCreditService creditService;
    @Mock private PaymentService paymentService;

    @InjectMocks private PortalDebtService service;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);
    private final UserDetailsImpl dealer = actor(50, "ROLE_CUSTOMER");
    private Customer c;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atStartOfDay(PortalDebtService.VN_ZONE).plusHours(9).toInstant(),
                PortalDebtService.VN_ZONE);
        c = customer(6, null);
        c.setCreditLimit(new BigDecimal("10000000"));
        c.setMaxDebtDays(30);
    }

    private void linked() {
        when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(c));
    }

    private static Invoice invoice(long id, LocalDate date, String total, String remaining, String status) {
        Invoice inv = Invoice.builder().id(id).code("HD2026-00000" + id).invoiceDate(date)
                .totalAmount(new BigDecimal(total)).build();
        inv.setRemainingAmount(remaining == null ? null : new BigDecimal(remaining));
        inv.setPaidAmount(remaining == null ? null : new BigDecimal(total).subtract(new BigDecimal(remaining)));
        inv.setPaymentStatus(status);
        List<InvoiceLine> lines = new ArrayList<>();
        lines.add(InvoiceLine.builder().orderCode("DH" + id).build());
        lines.add(InvoiceLine.builder().orderCode("DH" + id).build());
        inv.setLines(lines);
        return inv;
    }

    private static CreditStatusResponse credit(String debt, boolean overdue) {
        BigDecimal l = new BigDecimal("10000000");
        BigDecimal d = new BigDecimal(debt);
        return new CreditStatusResponse(6L, "DL-6", "Đại lý 6", l, d, l.subtract(d), BigDecimal.ZERO, d, false,
                BigDecimal.ZERO, 30, overdue, overdue ? 1 : 0, overdue ? new BigDecimal("700000") : BigDecimal.ZERO,
                overdue ? 5 : 0, overdue, overdue ? "Có nợ quá hạn" : null);
    }

    @Test
    @DisplayName("S7-07: Tổng còn nợ, hạn mức còn lại, nợ trên hoá đơn / chưa xuất hoá đơn, hạn trả gần nhất")
    void summary_ok() {
        linked();
        when(creditService.evaluate(c, BigDecimal.ZERO)).thenReturn(credit("3000000", false));
        when(invoiceRepository.sumOpenRemainingByCustomer(6L)).thenReturn(new BigDecimal("2000000"));
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(6L)).thenReturn(List.of(
                invoice(1, LocalDate.of(2026, 10, 1), "1500000", "1500000", Invoice.PAYMENT_STATUS_UNPAID),
                invoice(2, LocalDate.of(2026, 10, 5), "900000", "500000", Invoice.PAYMENT_STATUS_PARTIALLY_PAID)));

        PortalDebtSummaryResponse res = service.summary(dealer);

        assertThat(res.customerId()).isEqualTo(6L);
        assertThat(res.creditLimit()).isEqualByComparingTo("10000000");
        assertThat(res.currentDebt()).isEqualByComparingTo("3000000");
        assertThat(res.availableCredit()).isEqualByComparingTo("7000000");
        assertThat(res.invoicedDebt()).isEqualByComparingTo("2000000");
        assertThat(res.uninvoicedDebt()).isEqualByComparingTo("1000000");
        assertThat(res.openInvoiceCount()).isEqualTo(2);
        assertThat(res.overdue()).isFalse();
        // hoá đơn 01/10 + 30 ngày = 31/10 là hạn gần nhất
        assertThat(res.nextDueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(res.nextDueAmount()).isEqualByComparingTo("1500000");
    }

    @Test
    @DisplayName("S7-07: Có nợ quá hạn -> báo số tiền, số ngày; hạn gần nhất bỏ qua hoá đơn đã quá hạn")
    void summary_overdue() {
        linked();
        when(creditService.evaluate(c, BigDecimal.ZERO)).thenReturn(credit("1200000", true));
        when(invoiceRepository.sumOpenRemainingByCustomer(6L)).thenReturn(new BigDecimal("1200000"));
        when(invoiceRepository.findUnpaidByCustomerIdOrderByInvoiceDateAsc(6L)).thenReturn(List.of(
                invoice(1, LocalDate.of(2026, 9, 11), "700000", "700000", Invoice.PAYMENT_STATUS_UNPAID),
                invoice(2, LocalDate.of(2026, 10, 10), "500000", "500000", Invoice.PAYMENT_STATUS_UNPAID)));

        PortalDebtSummaryResponse res = service.summary(dealer);

        assertThat(res.overdue()).isTrue();
        assertThat(res.overdueAmount()).isEqualByComparingTo("700000");
        assertThat(res.overdueDays()).isEqualTo(5);
        assertThat(res.uninvoicedDebt()).isEqualByComparingTo("0");
        assertThat(res.nextDueDate()).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(res.nextDueAmount()).isEqualByComparingTo("500000");
    }

    @Test
    @DisplayName("S7-07: Tài khoản đại lý chưa gắn hồ sơ -> 404, không lộ dữ liệu đại lý khác")
    void notLinked_404() {
        assertThatThrownBy(() -> service.summary(dealer))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
        verifyNoInteractions(invoiceRepository, creditService, paymentService);
    }

    @Test
    @DisplayName("S7-07: Hoá đơn: hạn thanh toán, quá hạn bao nhiêu ngày, trạng thái, mã đơn không trùng")
    void toInvoice_dueAndStatus() {
        CustomerInvoiceDebtResponse overdue = PortalDebtService.toInvoice(
                invoice(1, LocalDate.of(2026, 9, 1), "1000000", "400000", Invoice.PAYMENT_STATUS_PARTIALLY_PAID), 30, TODAY);
        CustomerInvoiceDebtResponse paid = PortalDebtService.toInvoice(
                invoice(2, LocalDate.of(2026, 9, 1), "1000000", "0", Invoice.PAYMENT_STATUS_PAID), 30, TODAY);
        CustomerInvoiceDebtResponse legacy = PortalDebtService.toInvoice(
                invoice(3, LocalDate.of(2026, 10, 10), "800000", null, null), 30, TODAY);

        assertThat(overdue.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(overdue.isOverdue()).isTrue();
        assertThat(overdue.overdueDays()).isEqualTo(15);
        assertThat(overdue.paidAmount()).isEqualByComparingTo("600000");
        assertThat(overdue.remainingAmount()).isEqualByComparingTo("400000");
        assertThat(overdue.status()).isEqualTo(Invoice.PAYMENT_STATUS_PARTIALLY_PAID);
        assertThat(overdue.orderCodes()).containsExactly("DH1");

        assertThat(paid.isOverdue()).isFalse();
        assertThat(paid.status()).isEqualTo(Invoice.PAYMENT_STATUS_PAID);

        // hoá đơn trước S7-02 chưa có số đã trả -> coi như còn nợ toàn bộ
        assertThat(legacy.remainingAmount()).isEqualByComparingTo("800000");
        assertThat(legacy.paidAmount()).isEqualByComparingTo("0");
        assertThat(legacy.status()).isEqualTo(Invoice.PAYMENT_STATUS_UNPAID);
    }

    @Test
    @DisplayName("S7-07: Đại lý chưa đặt số ngày nợ tối đa -> hoá đơn không có hạn, không bị tính quá hạn")
    void toInvoice_noMaxDays() {
        CustomerInvoiceDebtResponse r = PortalDebtService.toInvoice(
                invoice(1, LocalDate.of(2026, 1, 1), "1000000", "1000000", Invoice.PAYMENT_STATUS_UNPAID), null, TODAY);

        assertThat(r.dueDate()).isNull();
        assertThat(r.isOverdue()).isFalse();
    }

    @Test
    @DisplayName("S7-07: Danh sách hoá đơn của đại lý (phân trang, map đúng)")
    @SuppressWarnings("unchecked")
    void invoices_ok() {
        linked();
        when(invoiceRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                invoice(1, LocalDate.of(2026, 10, 1), "1500000", "1500000", Invoice.PAYMENT_STATUS_UNPAID))));

        PageResponse<CustomerInvoiceDebtResponse> res = service.invoices(null, 0, 500, dealer);

        assertThat(res.content()).hasSize(1);
        assertThat(res.content().get(0).code()).isEqualTo("HD2026-000001");
        assertThat(res.content().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    }

    @Test
    @DisplayName("S7-07: Bộ lọc hoá đơn sai -> 400")
    void invoices_badFilter() {
        linked();

        assertThatThrownBy(() -> service.invoices("abc", 0, 20, dealer))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_INVOICE_FILTER");
    }

    @Test
    @DisplayName("S7-07: Lọc quá hạn khi đại lý không có số ngày nợ tối đa -> rỗng, không truy vấn")
    void invoices_overdueWithoutMaxDays_empty() {
        c.setMaxDebtDays(null);
        linked();

        assertThat(service.invoices("overdue", 0, 20, dealer).content()).isEmpty();
        verifyNoInteractions(invoiceRepository);
    }

    @Test
    @DisplayName("S7-07: Phiếu thu chỉ lấy của đúng đại lý đăng nhập")
    void payments_scopedToOwnCustomer() {
        linked();

        service.payments(0, 20, dealer);

        verify(paymentService).searchReceipts(eq(6L), isNull(), isNull(), isNull(), isNull(), isNull(),
                eq(0), eq(20), eq(dealer));
    }
}
