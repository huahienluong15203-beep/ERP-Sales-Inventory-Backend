package com.erp.backend.service;

import com.erp.backend.dto.invoice.InvoiceIssueRequest;
import com.erp.backend.dto.invoice.InvoiceResponse;
import com.erp.backend.dto.invoice.UninvoicedOrderResponse;
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

/** S7-01: Phát hành hoá đơn từ số thực giao, số hoá đơn liên tục, gộp nhiều đơn, không lập 2 lần. */
@ExtendWith(MockitoExtension.class)
class InvoiceServiceTest {

    @Mock private InvoiceRepository invoiceRepository;
    @Mock private InvoiceLineRepository invoiceLineRepository;
    @Mock private InvoiceSequenceRepository sequenceRepository;
    @Mock private SalesOrderRepository orderRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private InvoiceService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);

    private final UserDetailsImpl accountant = actor(40, "ROLE_ACCOUNTANT");
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca").baseUnit("Lon").build();
    private final Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").build();

    private Customer cust;
    private SalesOrder o1;
    private SalesOrder o2;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(TODAY.atTime(10, 0).atZone(VN).toInstant(), VN);
        cust = customer(6L, salesRep(7L));
        cust.setTaxCode("0101234567");
        cust.setAddress("Số 1 Hà Nội");
        // Đơn 1: coca 10 thùng giao đủ; pepsi 50 lon giao 40
        o1 = order(101L, "DH261010-AAAA", LocalDateTime.of(2026, 10, 10, 15, 0));
        o1.addLine(line(1001L, 1, coca, "Thùng", "24", "10", "240", "2400000.00", "100000.00", "10"));
        o1.addLine(line(1002L, 2, pepsi, "Lon", "1", "50", "50", "500000.00", "0.00", "40"));
        // Đơn 2: pepsi 20 lon giao đủ
        o2 = order(102L, "DH261012-BBBB", LocalDateTime.of(2026, 10, 12, 9, 0));
        o2.addLine(line(1003L, 1, pepsi, "Lon", "1", "20", "20", "200000.00", "0.00", "20"));
    }

    private SalesOrder order(long id, String code, LocalDateTime deliveredAt) {
        return SalesOrder.builder().id(id).code(code).customer(cust).status(SalesOrder.STATUS_DELIVERED)
                .deliveredAt(deliveredAt).lines(new ArrayList<>()).build();
    }

    private static SalesOrderLine line(long id, int no, Product p, String unit, String factor, String qty, String base,
                                       String gross, String discount, String delivered) {
        BigDecimal g = new BigDecimal(gross);
        BigDecimal d = new BigDecimal(discount);
        return SalesOrderLine.builder().id(id).lineNo(no).product(p).productSku(p.getSku()).productName(p.getName())
                .unitName(unit).conversionFactor(new BigDecimal(factor)).quantity(new BigDecimal(qty))
                .baseUnit("Lon").baseQuantity(new BigDecimal(base))
                .pricePerUnit(g.divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP))
                .unitPrice(new BigDecimal("10000")).floorPrice(BigDecimal.ZERO)
                .grossAmount(g).discountAmount(d).netAmount(g.subtract(d))
                .deliveredQuantity(delivered != null ? new BigDecimal(delivered) : null)
                .build();
    }

    private void stubIssue(long lastNumber, LocalDate lastDate) {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(new InvoiceSequence(2026, lastNumber)));
        when(invoiceRepository.maxInvoiceDate(2026)).thenReturn(lastDate);
        when(invoiceRepository.saveAndFlush(any(Invoice.class))).thenAnswer(i -> {
            Invoice inv = i.getArgument(0);
            inv.setId(900L);
            return inv;
        });
    }

    private InvoiceIssueRequest request(Long... orderIds) {
        return InvoiceIssueRequest.builder().customerId(6L).orderIds(List.of(orderIds)).build();
    }

    // ======================= DỰNG DÒNG HOÁ ĐƠN =======================

    @Test
    @DisplayName("S7-01: Dòng hoá đơn lấy SỐ THỰC GIAO: giao đủ giữ nguyên tiền, giao 40/50 chia tiền theo tỉ lệ")
    void buildLines_usesDeliveredQuantity() {
        List<InvoiceLine> lines = InvoiceService.buildLines(List.of(o1, o2));

        assertThat(lines).hasSize(3);
        InvoiceLine coke = lines.get(0);
        assertThat(coke.getLineNo()).isEqualTo(1);
        assertThat(coke.getQuantity()).isEqualByComparingTo("10");
        assertThat(coke.getBaseQuantity()).isEqualByComparingTo("240");
        assertThat(coke.getGrossAmount()).isEqualByComparingTo("2400000");
        assertThat(coke.getDiscountAmount()).isEqualByComparingTo("100000");
        assertThat(coke.getNetAmount()).isEqualByComparingTo("2300000");
        assertThat(coke.getOrderCode()).isEqualTo("DH261010-AAAA");
        assertThat(coke.getOrderLineId()).isEqualTo(1001L);
        InvoiceLine pep = lines.get(1);
        assertThat(pep.getQuantity()).isEqualByComparingTo("40");
        assertThat(pep.getBaseQuantity()).isEqualByComparingTo("40");
        assertThat(pep.getGrossAmount()).isEqualByComparingTo("400000");
        assertThat(pep.getNetAmount()).isEqualByComparingTo("400000");
        assertThat(lines.get(2).getLineNo()).isEqualTo(3);
        assertThat(lines.get(2).getOrderCode()).isEqualTo("DH261012-BBBB");
    }

    @Test
    @DisplayName("S7-01: Dòng giao 0 bị bỏ; dòng chưa ghi nhận số thực giao -> 409")
    void buildLines_skipsZero_rejectsMissing() {
        o2.getLines().get(0).setDeliveredQuantity(BigDecimal.ZERO);
        assertThat(InvoiceService.buildLines(List.of(o2))).isEmpty();

        o2.getLines().get(0).setDeliveredQuantity(null);
        assertThatThrownBy(() -> InvoiceService.buildLines(List.of(o2)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_NOT_RECORDED");
    }

    // ======================= PHÁT HÀNH =======================

    @Test
    @DisplayName("S7-01: Gộp 2 đơn giao trong kỳ vào 1 hoá đơn, cấp số kế tiếp HD2026-000042, đánh dấu đơn đã lập hoá đơn")
    void issue_mergesOrdersAndAssignsNextNumber() {
        stubIssue(41L, LocalDate.of(2026, 10, 14));
        when(orderRepository.findAllByIdForUpdate(List.of(101L, 102L))).thenReturn(List.of(o2, o1));

        InvoiceResponse res = service.issue(request(102L, 101L, 101L), accountant);

        assertThat(res.code()).isEqualTo("HD2026-000042");
        assertThat(res.invoiceDate()).isEqualTo(TODAY);
        assertThat(res.customerTaxCode()).isEqualTo("0101234567");
        assertThat(res.customerAddress()).isEqualTo("Số 1 Hà Nội");
        // Sắp theo thời điểm giao: đơn 1 (10/10) trước đơn 2 (12/10)
        assertThat(res.orderCodes()).containsExactly("DH261010-AAAA", "DH261012-BBBB");
        assertThat(res.periodFrom()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(res.periodTo()).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(res.lines()).hasSize(3);
        // 2.400.000 + 400.000 + 200.000 = 3.000.000; chiết khấu 100.000 -> 2.900.000
        assertThat(res.subtotal()).isEqualByComparingTo("3000000");
        assertThat(res.discountTotal()).isEqualByComparingTo("100000");
        assertThat(res.totalAmount()).isEqualByComparingTo("2900000");

        verify(sequenceRepository).ensureYear(2026);
        ArgumentCaptor<InvoiceSequence> seq = ArgumentCaptor.forClass(InvoiceSequence.class);
        verify(sequenceRepository).save(seq.capture());
        assertThat(seq.getValue().getLastNumber()).isEqualTo(42L);
        ArgumentCaptor<Invoice> inv = ArgumentCaptor.forClass(Invoice.class);
        verify(invoiceRepository).saveAndFlush(inv.capture());
        assertThat(inv.getValue().getSeqYear()).isEqualTo(2026);
        assertThat(inv.getValue().getSeqNumber()).isEqualTo(42L);
        assertThat(inv.getValue().getLines()).allMatch(l -> l.getInvoice() == inv.getValue());
        assertThat(o1.getInvoiceId()).isEqualTo(900L);
        assertThat(o1.getInvoiceCode()).isEqualTo("HD2026-000042");
        assertThat(o2.getInvoiceCode()).isEqualTo("HD2026-000042");
        verify(orderRepository).saveAll(anyList());
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("ISSUE_INVOICE"), eq("INVOICE"), eq(900L),
                eq("HD2026-000042"), isNull(), contains("Tổng=2900000"), anyString(), eq(accountant));
    }

    @Test
    @DisplayName("S7-01: Năm mới chưa có bộ đếm -> số đầu tiên HD2026-000001")
    void issue_firstInvoiceOfYear() {
        stubIssue(0L, null);
        when(orderRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(o2));

        assertThat(service.issue(request(102L), accountant).code()).isEqualTo("HD2026-000001");
    }

    @Test
    @DisplayName("S7-01: Không chọn đơn -> gộp mọi đơn đã giao chưa lập hoá đơn trong kỳ")
    void issue_byPeriod_usesUninvoicedIds() {
        stubIssue(5L, null);
        when(orderRepository.findUninvoicedIds(eq(6L), anyList(), eq(LocalDate.of(2026, 10, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 11, 1).atStartOfDay()))).thenReturn(List.of(102L, 101L));
        when(orderRepository.findAllByIdForUpdate(List.of(101L, 102L))).thenReturn(List.of(o1, o2));

        InvoiceResponse res = service.issue(InvoiceIssueRequest.builder().customerId(6L)
                .fromDate(LocalDate.of(2026, 10, 1)).toDate(LocalDate.of(2026, 10, 31)).build(), accountant);

        assertThat(res.code()).isEqualTo("HD2026-000006");
        assertThat(res.orderCodes()).hasSize(2);
    }

    @Test
    @DisplayName("S7-01: Ngày hoá đơn trước ngày hoá đơn gần nhất -> 409, không tăng số (không nhảy số)")
    void issue_dateBeforeLast_conflict() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(o2));
        when(sequenceRepository.findForUpdate(2026)).thenReturn(Optional.of(new InvoiceSequence(2026, 41L)));
        when(invoiceRepository.maxInvoiceDate(2026)).thenReturn(LocalDate.of(2026, 10, 14));

        assertThatThrownBy(() -> service.issue(InvoiceIssueRequest.builder().customerId(6L).orderIds(List.of(102L))
                .invoiceDate(LocalDate.of(2026, 10, 13)).build(), accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVOICE_DATE_BEFORE_LAST");
        verify(sequenceRepository, never()).save(any());
        verify(invoiceRepository, never()).saveAndFlush(any());
        assertThat(o2.getInvoiceId()).isNull();
    }

    @Test
    @DisplayName("S7-01: Đơn đã lập hoá đơn / chưa giao / của đại lý khác -> chặn, không cấp số")
    void issue_invalidOrders_rejected() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(o2));

        o2.setInvoiceId(5L);
        o2.setInvoiceCode("HD2026-000005");
        assertThatThrownBy(() -> service.issue(request(102L), accountant))
                .hasFieldOrPropertyWithValue("code", "ORDER_ALREADY_INVOICED");

        o2.setInvoiceId(null);
        o2.setStatus(SalesOrder.STATUS_DISPATCHED);
        assertThatThrownBy(() -> service.issue(request(102L), accountant))
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_DELIVERED");

        o2.setStatus(SalesOrder.STATUS_CLOSED);
        o2.setCustomer(customer(8L, salesRep(7L)));
        assertThatThrownBy(() -> service.issue(request(102L), accountant))
                .hasFieldOrPropertyWithValue("code", "ORDER_CUSTOMER_MISMATCH");
        verifyNoInteractions(sequenceRepository);
    }

    @Test
    @DisplayName("S7-01: Dòng đơn đã có trên hoá đơn khác (kiểm lại ở DB) -> 409")
    void issue_lineAlreadyOnInvoice_conflict() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(o2));
        when(invoiceLineRepository.existsByOrderLineIdIn(List.of(1003L))).thenReturn(true);

        assertThatThrownBy(() -> service.issue(request(102L), accountant))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "ORDER_ALREADY_INVOICED");
        verifyNoInteractions(sequenceRepository);
    }

    @Test
    @DisplayName("S7-01: Không có đơn nào để lập / đơn không tồn tại -> chặn")
    void issue_noOrders() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findUninvoicedIds(eq(6L), anyList(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.issue(InvoiceIssueRequest.builder().customerId(6L).build(), accountant))
                .hasFieldOrPropertyWithValue("code", "NO_ORDERS_TO_INVOICE");

        when(orderRepository.findAllByIdForUpdate(List.of(999L))).thenReturn(List.of());
        assertThatThrownBy(() -> service.issue(request(999L), accountant))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("không tồn tại");
    }

    @Test
    @DisplayName("S7-01: Ngày hoá đơn ở tương lai -> 400")
    void issue_futureDate_badRequest() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        assertThatThrownBy(() -> service.issue(InvoiceIssueRequest.builder().customerId(6L).orderIds(List.of(102L))
                .invoiceDate(TODAY.plusDays(1)).build(), accountant))
                .hasFieldOrPropertyWithValue("code", "INVOICE_DATE_IN_FUTURE");
        verifyNoInteractions(orderRepository, sequenceRepository);
    }

    @Test
    @DisplayName("S7-01: QL kinh doanh / NV kinh doanh không phát hành hoá đơn được -> 403")
    void issue_wrongRole_forbidden() {
        assertThatThrownBy(() -> service.issue(request(102L), actor(3, "ROLE_SALES_MANAGER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVOICE_ISSUE_NOT_ALLOWED");
        verifyNoInteractions(customerRepository, orderRepository, sequenceRepository, invoiceRepository);
    }

    @Test
    @DisplayName("S7-01: Xem trước không cấp số, không lưu, không khoá đơn")
    void preview_doesNotPersist() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findAllById(List.of(102L))).thenReturn(List.of(o2));

        InvoiceResponse res = service.preview(request(102L), accountant);

        assertThat(res.code()).isNull();
        assertThat(res.totalAmount()).isEqualByComparingTo("200000");
        verify(orderRepository, never()).findAllByIdForUpdate(any());
        verifyNoInteractions(sequenceRepository, invoiceRepository, invoiceLineRepository, auditLogService);
    }

    @Test
    @DisplayName("S7-01: Danh sách đơn chưa lập hoá đơn kèm tiền theo số thực giao")
    void uninvoicedOrders_listsDeliveredAmount() {
        when(customerRepository.findById(6L)).thenReturn(Optional.of(cust));
        when(orderRepository.findUninvoiced(eq(6L), anyList(), any(), any())).thenReturn(List.of(o1));

        List<UninvoicedOrderResponse> res = service.uninvoicedOrders(6L, null, null, accountant);

        assertThat(res).hasSize(1);
        // coca 2.300.000 + pepsi 40/50 x 500.000 = 2.700.000
        assertThat(res.get(0).deliveredAmount()).isEqualByComparingTo("2700000");
    }

    @Test
    @DisplayName("S7-01: NV kinh doanh xem hoá đơn của đại lý người khác phụ trách -> 404")
    void get_otherRep_hidden() {
        Invoice inv = Invoice.builder().id(900L).code("HD2026-000001").customer(cust).lines(new ArrayList<>()).build();
        when(invoiceRepository.findById(900L)).thenReturn(Optional.of(inv));

        assertThatThrownBy(() -> service.get(900L, actor(9, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_NOT_FOUND");
    }

    @Test
    @DisplayName("S7-01: Mã hoá đơn đủ 6 chữ số theo năm")
    void invoiceCode_format() {
        assertThat(InvoiceService.invoiceCode(2026, 7)).isEqualTo("HD2026-000007");
        assertThat(InvoiceService.invoiceCode(2027, 1234567)).isEqualTo("HD2027-1234567");
    }

    @Test
    @DisplayName("S7-01: Mẫu in hoá đơn escape dữ liệu người nhập (chống chèn mã)")
    void print_escapesHtml() {
        InvoiceResponse r = new InvoiceResponse(1L, "HD2026-000001", TODAY, "ISSUED", 6L, "DL-6",
                "<script>alert(1)</script>", null, null, TODAY, TODAY, List.of("DH1"), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "<b>x</b>", null, "acc");

        String html = InvoicePrintService.render(r, false);

        assertThat(html).contains("HOÁ ĐƠN BÁN HÀNG").contains("HD2026-000001")
                .doesNotContain("<script>alert(1)</script>").contains("&lt;script&gt;");
        assertThat(html).doesNotContain("window.print");
    }
}
