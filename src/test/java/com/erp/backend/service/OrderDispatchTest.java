package com.erp.backend.service;

import com.erp.backend.dto.order.OrderDispatchRequest;
import com.erp.backend.dto.order.OrderDispatchResponse;
import com.erp.backend.dto.order.OrderLineDispatchRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
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

/** S6-05: Xác nhận xuất kho, xuất thiếu tách đơn nợ hàng, chứng từ đã xuất không sửa được. */
@ExtendWith(MockitoExtension.class)
class OrderDispatchTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private com.erp.backend.repository.CustomerRepository customerRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AuditLogService auditLogService;
    @Mock private OrderDraftService orderDraftService;
    @Mock private com.erp.backend.repository.UserRepository userRepository;

    @InjectMocks private OrderStatusService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 14, 0);
    private static final LocalDateTime APPROVED_AT = LocalDateTime.of(2026, 10, 12, 9, 0);

    private final UserDetailsImpl whManager = actor(30, "ROLE_WH_MANAGER");
    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca").baseUnit("Lon").build();
    private final Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").build();

    private SalesOrder order;
    private SalesOrderLine cocaLine;
    private SalesOrderLine pepsiLine;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
        order = SalesOrder.builder()
                .id(100L).code("DH261012-AB12").customer(customer(6L, salesRep(7L)))
                .status(SalesOrder.STATUS_PICKING)
                .approvedAt(APPROVED_AT).approvedByUsername("ql")
                .reservedWarehouse(hn).reservationStatus(SalesOrder.RESERVATION_RESERVED)
                .lines(new ArrayList<>())
                .build();
        // 10 Thùng x 24 = 240 Lon, 240.000 ₫/thùng, chiết khấu 100.000 ₫
        cocaLine = line(1L, 1, coca, "Thùng", "24", "10", "240", "2400000.00", "100000.00");
        // 50 Lon, 10.000 ₫/lon, không chiết khấu
        pepsiLine = line(2L, 2, pepsi, "Lon", "1", "50", "50", "500000.00", "0.00");
        order.addLine(cocaLine);
        order.addLine(pepsiLine);
        order.setSubtotal(new BigDecimal("2900000.00"));
        order.setDiscountTotal(new BigDecimal("100000.00"));
        order.setTotalAmount(new BigDecimal("2800000.00"));
    }

    private static SalesOrderLine line(long id, int no, Product p, String unit, String factor, String qty, String base,
                                       String gross, String discount) {
        BigDecimal g = new BigDecimal(gross);
        BigDecimal d = new BigDecimal(discount);
        return SalesOrderLine.builder().id(id).lineNo(no).product(p).productSku(p.getSku()).productName(p.getName())
                .unitName(unit).conversionFactor(new BigDecimal(factor)).quantity(new BigDecimal(qty))
                .baseUnit("Lon").baseQuantity(new BigDecimal(base))
                .unitPrice(new BigDecimal("10000")).pricePerUnit(g.divide(new BigDecimal(qty), 2, java.math.RoundingMode.HALF_UP))
                .floorPrice(new BigDecimal("9000")).grossAmount(g).discountAmount(d).netAmount(g.subtract(d))
                .discountPolicyCode(d.signum() > 0 ? "CK-COCA" : null)
                .build();
    }

    private static OrderLineDispatchRequest lineReq(long lineId, String qty, String reason) {
        return OrderLineDispatchRequest.builder().lineId(lineId).dispatchedQuantity(new BigDecimal(qty))
                .shortageReason(reason).build();
    }

    private void stubOrder() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
    }

    private void stubSave() {
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(i -> i.getArgument(0));
    }

    @SuppressWarnings("unchecked")
    private List<InventoryService.DispatchLine> capturedDispatch() {
        ArgumentCaptor<List<InventoryService.DispatchLine>> c = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).dispatchStock(eq(order), c.capture(), eq(whManager));
        return c.getValue();
    }

    @Test
    @DisplayName("S6-05: Không gửi dòng nào -> xuất đủ: trừ đúng số giữ chỗ, đơn Đã xuất, ghi người / giờ xuất, không tách đơn")
    void dispatch_full_noBackorder() {
        stubOrder();
        stubSave();
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(mock(OrderResponse.class));

        OrderDispatchResponse res = service.dispatch(100L, null, whManager);

        assertThat(res.backorder()).isNull();
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DISPATCHED);
        assertThat(order.getDispatchedAt()).isEqualTo(NOW);
        assertThat(order.getDispatchedByUsername()).isEqualTo(whManager.getUsername());
        List<InventoryService.DispatchLine> d = capturedDispatch();
        assertThat(d).hasSize(2);
        assertThat(d.get(0).baseQuantity()).isEqualByComparingTo("240");
        assertThat(d.get(1).baseQuantity()).isEqualByComparingTo("50");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("2800000");
        verify(orderRepository, times(1)).saveAndFlush(any(SalesOrder.class));
        verify(auditLogService, never()).record(any(), eq("DISPATCH_SHORTAGE"), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("S6-05: Xuất thiếu 7/10 thùng -> dòng còn 7 thùng (168 lon), phần nợ 3 thùng tách sang đơn nợ hàng, tổng 2 đơn = đơn cũ")
    void dispatch_short_createsBackorder() {
        stubOrder();
        stubSave();
        when(orderRepository.existsByCode("DH261012-AB12-N1")).thenReturn(false);
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(mock(OrderResponse.class));

        OrderDispatchRequest req = OrderDispatchRequest.builder()
                .lines(List.of(lineReq(1L, "7", " Kho chỉ còn 7 thùng "))).build();
        OrderDispatchResponse res = service.dispatch(100L, req, whManager);

        // Trừ tồn: coca 168 lon (7 x 24), pepsi đủ 50
        List<InventoryService.DispatchLine> d = capturedDispatch();
        assertThat(d.get(0).line()).isSameAs(cocaLine);
        assertThat(d.get(0).baseQuantity()).isEqualByComparingTo("168");
        assertThat(d.get(1).baseQuantity()).isEqualByComparingTo("50");

        // Dòng của đơn gốc giữ đúng số thực xuất và tiền theo tỉ lệ
        assertThat(cocaLine.getOrderedQuantity()).isEqualByComparingTo("10");
        assertThat(cocaLine.getQuantity()).isEqualByComparingTo("7");
        assertThat(cocaLine.getBaseQuantity()).isEqualByComparingTo("168");
        assertThat(cocaLine.getBackorderQuantity()).isEqualByComparingTo("3");
        assertThat(cocaLine.getGrossAmount()).isEqualByComparingTo("1680000");
        assertThat(cocaLine.getDiscountAmount()).isEqualByComparingTo("70000");
        assertThat(cocaLine.getNetAmount()).isEqualByComparingTo("1610000");
        assertThat(cocaLine.getDispatchShortageReason()).isEqualTo("Kho chỉ còn 7 thùng");
        assertThat(pepsiLine.getOrderedQuantity()).isNull();
        assertThat(order.getTotalAmount()).isEqualByComparingTo("2110000");
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DISPATCHED);

        // Đơn nợ hàng
        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        verify(orderRepository, times(2)).saveAndFlush(saved.capture());
        SalesOrder bo = saved.getAllValues().get(1);
        assertThat(bo.getCode()).isEqualTo("DH261012-AB12-N1");
        assertThat(bo.getStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        assertThat(bo.isBackorderOrder()).isTrue();
        assertThat(bo.getParentOrder()).isSameAs(order);
        assertThat(bo.getParentOrderCode()).isEqualTo("DH261012-AB12");
        assertThat(bo.getApprovedAt()).isEqualTo(APPROVED_AT);
        assertThat(bo.getReservationStatus()).isEqualTo(SalesOrder.RESERVATION_RELEASED);
        assertThat(bo.getReservedWarehouse()).isSameAs(hn);
        assertThat(bo.getCustomer()).isSameAs(order.getCustomer());
        assertThat(bo.getLines()).hasSize(1);
        SalesOrderLine bl = bo.getLines().get(0);
        assertThat(bl.getOrder()).isSameAs(bo);
        assertThat(bl.getLineNo()).isEqualTo(1);
        assertThat(bl.getQuantity()).isEqualByComparingTo("3");
        assertThat(bl.getBaseQuantity()).isEqualByComparingTo("72");
        assertThat(bl.getGrossAmount()).isEqualByComparingTo("720000");
        assertThat(bl.getDiscountAmount()).isEqualByComparingTo("30000");
        assertThat(bl.getNetAmount()).isEqualByComparingTo("690000");
        assertThat(bl.getDiscountPolicyCode()).isEqualTo("CK-COCA");
        assertThat(bo.getTotalAmount()).isEqualByComparingTo("690000");
        // Tổng tiền 2 đơn đúng bằng đơn cũ -> công nợ không đổi
        assertThat(order.getTotalAmount().add(bo.getTotalAmount())).isEqualByComparingTo("2800000");
        assertThat(res.backorder()).isNotNull();

        // Lịch sử + nhật ký trước / sau
        ArgumentCaptor<SalesOrderApproval> hist = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository, times(2)).save(hist.capture());
        assertThat(hist.getAllValues().get(0).getComment()).contains("DH261012-AB12-N1");
        assertThat(hist.getAllValues().get(1).getAction()).isEqualTo("BACKORDER_CREATED");
        assertThat(hist.getAllValues().get(1).getOrder()).isSameAs(bo);
        verify(auditLogService).record(eq(AuditModule.ORDER), eq("DISPATCH_SHORTAGE"), eq("SALES_ORDER"), eq(100L),
                eq("DH261012-AB12"), contains("SP-COCA=10 Thùng"), contains("SP-COCA=7 Thùng"), anyString(), eq(whManager));
        verify(auditLogService).record(eq(AuditModule.ORDER), eq("CREATE_BACKORDER"), eq("SALES_ORDER"), any(),
                eq("DH261012-AB12-N1"), isNull(), contains("SP-COCA=3 Thùng"), anyString(), eq(whManager));
    }

    @Test
    @DisplayName("S6-05: Tiền chia lẻ vẫn khớp tổng: 3/7 của 100,00 -> giữ 42,86, nợ 57,14")
    void dispatch_short_roundingKeepsTotal() {
        order.getLines().clear();
        SalesOrderLine l = line(5L, 1, coca, "Lon", "1", "7", "7", "100.00", "0.00");
        order.addLine(l);
        order.setTotalAmount(new BigDecimal("100.00"));
        stubOrder();
        stubSave();
        when(orderRepository.existsByCode(anyString())).thenReturn(false);
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(mock(OrderResponse.class));

        service.dispatch(100L, OrderDispatchRequest.builder().lines(List.of(lineReq(5L, "3", "Thiếu hàng"))).build(), whManager);

        ArgumentCaptor<SalesOrder> saved = ArgumentCaptor.forClass(SalesOrder.class);
        verify(orderRepository, times(2)).saveAndFlush(saved.capture());
        SalesOrder bo = saved.getAllValues().get(1);
        assertThat(l.getGrossAmount()).isEqualByComparingTo("42.86");
        assertThat(bo.getLines().get(0).getGrossAmount()).isEqualByComparingTo("57.14");
        assertThat(order.getTotalAmount().add(bo.getTotalAmount())).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("S6-05: Đại lý không lấy phần thiếu (createBackorder=false) -> không tạo đơn nợ, tiền đơn giảm theo số thực xuất")
    void dispatch_short_withoutBackorder() {
        stubOrder();
        stubSave();
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(mock(OrderResponse.class));

        OrderDispatchResponse res = service.dispatch(100L, OrderDispatchRequest.builder()
                .lines(List.of(lineReq(2L, "20", null))).note("Đại lý chỉ lấy 20 lon").createBackorder(false).build(), whManager);

        assertThat(res.backorder()).isNull();
        assertThat(pepsiLine.getQuantity()).isEqualByComparingTo("20");
        assertThat(pepsiLine.getBackorderQuantity()).isNull();
        assertThat(pepsiLine.getDispatchShortageReason()).isEqualTo("Đại lý chỉ lấy 20 lon");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("2500000"); // 2.300.000 coca + 200.000 pepsi
        verify(orderRepository, times(1)).saveAndFlush(any(SalesOrder.class));
        verify(orderRepository, never()).existsByCode(anyString());
    }

    @Test
    @DisplayName("S6-05: Xuất thiếu mà không có lý do (dòng hoặc ghi chú) -> 400, chưa trừ tồn")
    void dispatch_shortWithoutReason_badRequest() {
        stubOrder();

        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(1L, "7", "  "))).build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DISPATCH_SHORTAGE_REASON_REQUIRED");
        verifyNoInteractions(inventoryService);
        assertThat(cocaLine.getQuantity()).isEqualByComparingTo("10");
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
    }

    @Test
    @DisplayName("S6-05: Số thực xuất vượt số đặt / âm / dòng lạ / gửi trùng dòng -> 400, chưa trừ tồn")
    void dispatch_invalidLines_badRequest() {
        stubOrder();

        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(1L, "11", null))).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "DISPATCH_EXCEEDS_ORDERED");
        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(1L, "-1", "x"))).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "INVALID_DISPATCH_QUANTITY");
        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(99L, "1", "x"))).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "ORDER_LINE_NOT_FOUND");
        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(1L, "5", "x"), lineReq(1L, "6", "x"))).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "DUPLICATE_DISPATCH_LINE");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-05: Mọi dòng xuất 0 -> 409 (không xuất phiếu rỗng, gợi ý huỷ đơn)")
    void dispatch_nothing_conflict() {
        stubOrder();

        assertThatThrownBy(() -> service.dispatch(100L, OrderDispatchRequest.builder()
                .lines(List.of(lineReq(1L, "0", "Hết hàng"), lineReq(2L, "0", "Hết hàng"))).build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DISPATCH_NOTHING");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-05: Phiếu xuất đã xác nhận (đơn Đã xuất) không xác nhận / sửa lại được")
    void dispatch_alreadyDispatched_locked() {
        order.setStatus(SalesOrder.STATUS_DISPATCHED);
        stubOrder();

        assertThatThrownBy(() -> service.dispatch(100L,
                OrderDispatchRequest.builder().lines(List.of(lineReq(1L, "5", "x"))).build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATUS_TRANSITION");
        verifyNoInteractions(inventoryService);
        assertThat(cocaLine.getQuantity()).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("S6-05: NV kinh doanh không xác nhận xuất kho -> 403")
    void dispatch_salesRep_forbidden() {
        assertThatThrownBy(() -> service.dispatch(100L, null, actor(7, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STATUS_CHANGE_NOT_ALLOWED");
        verifyNoInteractions(orderRepository, inventoryService);
    }

    @Test
    @DisplayName("S6-05: Mã đơn nợ đã có -N1 thì lấy -N2; đơn nợ của đơn nợ vẫn đánh theo mã gốc")
    void backorderCode_increments() {
        when(orderRepository.existsByCode("DH261012-AB12-N1")).thenReturn(true);
        when(orderRepository.existsByCode("DH261012-AB12-N2")).thenReturn(false);

        SalesOrder child = SalesOrder.builder().code("DH261012-AB12-N1").build();
        assertThat(service.backorderCode(order)).isEqualTo("DH261012-AB12-N2");
        assertThat(service.backorderCode(child)).isEqualTo("DH261012-AB12-N2");
    }

    @Test
    @DisplayName("S6-05: Chia tiền theo tỉ lệ làm tròn 2 số lẻ")
    void prorate_rounding() {
        assertThat(OrderStatusService.prorate(new BigDecimal("100.00"), new BigDecimal("1"), new BigDecimal("3")))
                .isEqualByComparingTo("33.33");
        assertThat(OrderStatusService.prorate(new BigDecimal("100.00"), BigDecimal.ONE, BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }
}
