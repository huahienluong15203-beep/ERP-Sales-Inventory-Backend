package com.erp.backend.service;

import com.erp.backend.dto.trip.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.DeliveryTripRepository;
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
import java.util.Set;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S6-07: Chuyến giao, tiến độ điểm giao, tiền hàng trên đường, cảnh báo trễ, hàng chưa giao thuộc chuyến. */
@ExtendWith(MockitoExtension.class)
class DeliveryTripServiceTest {

    @Mock private DeliveryTripRepository tripRepository;
    @Mock private SalesOrderRepository orderRepository;
    @Mock private InventoryService inventoryService;
    @Mock private StockLedgerService stockLedgerService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private DeliveryTripService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 16, 0);

    private final UserDetailsImpl whManager = actor(30, "ROLE_WH_MANAGER");
    private final Warehouse hn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();
    private final Warehouse hcm = Warehouse.builder().id(2L).code("WH-MN01").name("Kho Tổng Miền Nam").status("ACTIVE").build();
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca").baseUnit("Lon").build();

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
    }

    /** Đơn 10 Thùng x24, tiền dòng 2.400.000. */
    private SalesOrder order(long id, String status, Warehouse w) {
        SalesOrder o = SalesOrder.builder().id(id).code("DH" + id).customer(customer(6L, salesRep(7L)))
                .status(status).reservedWarehouse(w).totalAmount(new BigDecimal("2400000.00"))
                .lines(new ArrayList<>()).build();
        o.addLine(SalesOrderLine.builder().id(id * 10).lineNo(1).product(coca).productSku("SP-COCA").productName("Coca")
                .unitName("Thùng").conversionFactor(new BigDecimal("24")).quantity(new BigDecimal("10"))
                .baseUnit("Lon").baseQuantity(new BigDecimal("240"))
                .grossAmount(new BigDecimal("2400000.00")).discountAmount(BigDecimal.ZERO)
                .netAmount(new BigDecimal("2400000.00")).build());
        return o;
    }

    private DeliveryTrip trip(LocalDateTime expected) {
        return DeliveryTrip.builder().id(500L).code("CG261015-AAAA").warehouse(hn).driverName("Anh Tuấn")
                .status(DeliveryTrip.STATUS_IN_PROGRESS).departedAt(NOW.minusHours(5)).expectedReturnAt(expected).build();
    }

    private static DeliveryTripCreateRequest createReq(Long... ids) {
        return DeliveryTripCreateRequest.builder().orderIds(List.of(ids)).driverName(" Anh Tuấn ")
                .vehiclePlate("29H-12345").expectedReturnAt(NOW.plusHours(4)).build();
    }

    // ======================= LẬP CHUYẾN =======================

    @Test
    @DisplayName("S6-07: Lập chuyến cho 2 đơn đã xuất cùng kho -> chuyến đang chạy, 2 điểm còn lại, tiền trên đường = tổng 2 đơn")
    void create_success() {
        SalesOrder a = order(101, SalesOrder.STATUS_DISPATCHED, hn);
        SalesOrder b = order(102, SalesOrder.STATUS_DISPATCHED, hn);
        when(orderRepository.findAllByIdForUpdate(List.of(101L, 102L))).thenReturn(List.of(a, b));
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(Set.of(1L));
        when(tripRepository.save(any(DeliveryTrip.class))).thenAnswer(i -> {
            DeliveryTrip t = i.getArgument(0);
            t.setId(500L);
            return t;
        });

        DeliveryTripDetailResponse res = service.create(createReq(102L, 101L, 101L), whManager);

        assertThat(res.trip().code()).startsWith("CG261015-");
        assertThat(res.trip().status()).isEqualTo(DeliveryTrip.STATUS_IN_PROGRESS);
        assertThat(res.trip().driverName()).isEqualTo("Anh Tuấn");
        assertThat(res.trip().departedAt()).isEqualTo(NOW);
        assertThat(res.trip().totalStops()).isEqualTo(2);
        assertThat(res.trip().remainingStops()).isEqualTo(2);
        assertThat(res.trip().valueOnRoad()).isEqualByComparingTo("4800000");
        assertThat(res.trip().overdue()).isFalse();
        assertThat(a.getDeliveryTrip()).isNotNull();
        assertThat(b.getDeliveryTrip().getCode()).isEqualTo(res.trip().code());
        verify(orderRepository).saveAll(anyList());
    }

    @Test
    @DisplayName("S6-07: Đơn chưa xuất / đã trên chuyến khác / khác kho -> chặn, không lập chuyến")
    void create_invalidOrders() {
        SalesOrder picking = order(101, SalesOrder.STATUS_PICKING, hn);
        when(orderRepository.findAllByIdForUpdate(List.of(101L))).thenReturn(List.of(picking));
        assertThatThrownBy(() -> service.create(createReq(101L), whManager))
                .hasFieldOrPropertyWithValue("code", "ORDER_NOT_DISPATCHED");

        SalesOrder onTrip = order(102, SalesOrder.STATUS_DISPATCHED, hn);
        onTrip.setDeliveryTrip(trip(NOW.plusHours(1)));
        when(orderRepository.findAllByIdForUpdate(List.of(102L))).thenReturn(List.of(onTrip));
        assertThatThrownBy(() -> service.create(createReq(102L), whManager))
                .hasFieldOrPropertyWithValue("code", "ORDER_ALREADY_ON_TRIP");

        SalesOrder a = order(103, SalesOrder.STATUS_DISPATCHED, hn);
        SalesOrder b = order(104, SalesOrder.STATUS_DISPATCHED, hcm);
        when(orderRepository.findAllByIdForUpdate(List.of(103L, 104L))).thenReturn(List.of(a, b));
        assertThatThrownBy(() -> service.create(createReq(103L, 104L), whManager))
                .hasFieldOrPropertyWithValue("code", "ORDERS_DIFFERENT_WAREHOUSES");
        verify(tripRepository, never()).save(any());
    }

    @Test
    @DisplayName("S6-07: Thiếu tài xế / giờ dự kiến đã qua -> 400; NV kinh doanh lập chuyến -> 403")
    void create_invalidInput() {
        assertThatThrownBy(() -> service.create(DeliveryTripCreateRequest.builder().orderIds(List.of(1L))
                .driverName("  ").expectedReturnAt(NOW.plusHours(1)).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "DRIVER_REQUIRED");
        assertThatThrownBy(() -> service.create(DeliveryTripCreateRequest.builder().orderIds(List.of(1L))
                .driverName("A").expectedReturnAt(NOW.minusMinutes(1)).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "INVALID_EXPECTED_RETURN");
        assertThatThrownBy(() -> service.create(createReq(1L), actor(7, "ROLE_SALES_REP")))
                .hasFieldOrPropertyWithValue("code", "TRIP_NOT_ALLOWED");
        verifyNoInteractions(orderRepository, tripRepository);
    }

    @Test
    @DisplayName("S6-07: NV kho lập chuyến cho đơn của kho mình không được gắn -> 403")
    void create_otherWarehouse_forbidden() {
        UserDetailsImpl staff = actor(20, "ROLE_WAREHOUSE");
        when(orderRepository.findAllByIdForUpdate(List.of(101L))).thenReturn(List.of(order(101, SalesOrder.STATUS_DISPATCHED, hcm)));
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of(1L));

        assertThatThrownBy(() -> service.create(createReq(101L), staff))
                .hasFieldOrPropertyWithValue("code", "TRIP_WAREHOUSE_NOT_ASSIGNED");
        verify(tripRepository, never()).save(any());
    }

    // ======================= TIẾN ĐỘ / CẢNH BÁO =======================

    @Test
    @DisplayName("S6-07: Chuyến quá giờ dự kiến -> cảnh báo trễ; đếm đã giao / không thành công / còn lại; hàng chưa giao vẫn tính trên đường")
    void list_progressAndOverdue() {
        DeliveryTrip t = trip(NOW.minusMinutes(90));
        SalesOrder delivered = order(101, SalesOrder.STATUS_DELIVERED, hn);
        delivered.getLines().get(0).setDeliveredQuantity(new BigDecimal("8")); // thiếu 2 thùng, chưa nhập lại kho
        delivered.setDeliveryTrip(t);
        SalesOrder failed = order(102, SalesOrder.STATUS_DELIVERY_FAILED, hn);
        failed.getLines().get(0).setDeliveredQuantity(BigDecimal.ZERO);
        failed.setDeliveryTrip(t);
        SalesOrder remaining = order(103, SalesOrder.STATUS_DISPATCHED, hn);
        remaining.setDeliveryTrip(t);
        when(tripRepository.findByStatusWithWarehouse(DeliveryTrip.STATUS_IN_PROGRESS)).thenReturn(List.of(t));
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(null);
        when(orderRepository.findByDeliveryTripIds(List.of(500L))).thenReturn(List.of(delivered, failed, remaining));

        List<DeliveryTripSummaryResponse> res = service.list(null, null, whManager);

        assertThat(res).hasSize(1);
        DeliveryTripSummaryResponse s = res.get(0);
        assertThat(s.totalStops()).isEqualTo(3);
        assertThat(s.deliveredStops()).isEqualTo(1);
        assertThat(s.failedStops()).isEqualTo(1);
        assertThat(s.remainingStops()).isEqualTo(1);
        assertThat(s.overdue()).isTrue();
        assertThat(s.overdueMinutes()).isEqualTo(90);
        // 2 thùng chưa giao (480.000) + cả đơn không thành công (2.400.000) + đơn còn chở (2.400.000)
        assertThat(s.valueOnRoad()).isEqualByComparingTo("5280000");
    }

    @Test
    @DisplayName("S6-07: NV kho chỉ thấy chuyến của kho mình được gắn")
    void list_filteredByAssignedWarehouse() {
        DeliveryTrip mine = trip(NOW.plusHours(1));
        DeliveryTrip other = DeliveryTrip.builder().id(501L).code("CG-B").warehouse(hcm).driverName("B")
                .status(DeliveryTrip.STATUS_IN_PROGRESS).departedAt(NOW).expectedReturnAt(NOW.plusHours(1)).build();
        UserDetailsImpl staff = actor(20, "ROLE_WAREHOUSE");
        when(tripRepository.findByStatusWithWarehouse(DeliveryTrip.STATUS_IN_PROGRESS)).thenReturn(List.of(mine, other));
        when(stockLedgerService.allowedWarehouseIds(staff)).thenReturn(Set.of(1L));
        when(orderRepository.findByDeliveryTripIds(List.of(500L))).thenReturn(List.of());

        List<DeliveryTripSummaryResponse> res = service.list("in_progress", null, staff);

        assertThat(res).extracting(DeliveryTripSummaryResponse::code).containsExactly("CG261015-AAAA");
    }

    @Test
    @DisplayName("S6-07: Tổng quan hàng đi đường gồm cả đơn đã xuất chưa xếp chuyến")
    void summary_includesUnassigned() {
        DeliveryTrip late = trip(NOW.minusMinutes(1));
        SalesOrder onRoad = order(101, SalesOrder.STATUS_DISPATCHED, hn);
        onRoad.setDeliveryTrip(late);
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(null);
        when(tripRepository.findByStatusWithWarehouse(DeliveryTrip.STATUS_IN_PROGRESS)).thenReturn(List.of(late));
        when(orderRepository.findByDeliveryTripIds(List.of(500L))).thenReturn(List.of(onRoad));
        when(orderRepository.findByStatusWithoutTrip(SalesOrder.STATUS_DISPATCHED))
                .thenReturn(List.of(order(201, SalesOrder.STATUS_DISPATCHED, hn), order(202, SalesOrder.STATUS_DISPATCHED, hcm)));

        InTransitSummaryResponse s = service.summary(whManager);

        assertThat(s.activeTrips()).isEqualTo(1);
        assertThat(s.overdueTrips()).isEqualTo(1);
        assertThat(s.ordersOnRoad()).isEqualTo(1);
        assertThat(s.valueOnRoad()).isEqualByComparingTo("2400000");
        assertThat(s.unassignedOrders()).isEqualTo(2);
        assertThat(s.unassignedValue()).isEqualByComparingTo("4800000");
    }

    // ======================= KẾT THÚC CHUYẾN =======================

    @Test
    @DisplayName("S6-07: Còn điểm chưa ghi nhận giao -> không kết thúc được chuyến, không nhập kho")
    void complete_pendingStops_conflict() {
        DeliveryTrip t = trip(NOW.plusHours(1));
        SalesOrder pending = order(101, SalesOrder.STATUS_DISPATCHED, hn);
        when(tripRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(t));
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(null);
        when(orderRepository.findIdsByDeliveryTripId(500L)).thenReturn(List.of(101L));
        when(orderRepository.findAllByIdForUpdate(List.of(101L))).thenReturn(List.of(pending));

        assertThatThrownBy(() -> service.complete(500L, null, whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "TRIP_HAS_PENDING_STOPS")
                .hasMessageContaining("DH101");
        verifyNoInteractions(inventoryService);
        assertThat(t.getStatus()).isEqualTo(DeliveryTrip.STATUS_IN_PROGRESS);
    }

    @Test
    @DisplayName("S6-07: Kết thúc chuyến -> nhập lại kho phần chưa giao (2 thùng = 48 lon; đơn thất bại cả 240 lon), chuyến COMPLETED")
    @SuppressWarnings("unchecked")
    void complete_returnsUndeliveredStock() {
        DeliveryTrip t = trip(NOW.plusHours(1));
        SalesOrder shortOrder = order(101, SalesOrder.STATUS_DELIVERED, hn);
        shortOrder.getLines().get(0).setDeliveredQuantity(new BigDecimal("8"));
        SalesOrder failed = order(102, SalesOrder.STATUS_DELIVERY_FAILED, hn);
        failed.getLines().get(0).setDeliveredQuantity(BigDecimal.ZERO);
        SalesOrder full = order(103, SalesOrder.STATUS_DELIVERED, hn);
        full.getLines().get(0).setDeliveredQuantity(new BigDecimal("10"));
        SalesOrder alreadyReturned = order(104, SalesOrder.STATUS_DELIVERY_FAILED, hn);
        alreadyReturned.getLines().get(0).setDeliveredQuantity(BigDecimal.ZERO);
        alreadyReturned.setStockReturnedAt(NOW.minusHours(1));
        when(tripRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(t));
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(null);
        when(orderRepository.findIdsByDeliveryTripId(500L)).thenReturn(List.of(101L, 102L, 103L, 104L));
        when(orderRepository.findAllByIdForUpdate(List.of(101L, 102L, 103L, 104L)))
                .thenReturn(List.of(shortOrder, failed, full, alreadyReturned));
        when(tripRepository.save(any(DeliveryTrip.class))).thenAnswer(i -> i.getArgument(0));

        DeliveryTripDetailResponse res = service.complete(500L, new DeliveryTripCompleteRequest("Xe về lúc 16h"), whManager);

        ArgumentCaptor<List<InventoryService.DispatchLine>> c = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).returnDeliveredStock(eq(shortOrder), c.capture(), contains("CG261015-AAAA"), eq(whManager));
        assertThat(c.getValue().get(0).baseQuantity()).isEqualByComparingTo("48");
        verify(inventoryService).returnDeliveredStock(eq(failed), c.capture(), anyString(), eq(whManager));
        assertThat(c.getValue().get(0).baseQuantity()).isEqualByComparingTo("240");
        verify(inventoryService, never()).returnDeliveredStock(eq(full), any(), any(), any());
        verify(inventoryService, never()).returnDeliveredStock(eq(alreadyReturned), any(), any(), any());
        assertThat(shortOrder.getStockReturnedAt()).isEqualTo(NOW);
        assertThat(full.getStockReturnedAt()).isEqualTo(NOW);
        assertThat(t.getStatus()).isEqualTo(DeliveryTrip.STATUS_COMPLETED);
        assertThat(t.getCompletedAt()).isEqualTo(NOW);
        assertThat(res.trip().valueOnRoad()).isEqualByComparingTo("0");
        assertThat(res.trip().overdue()).isFalse();
    }

    @Test
    @DisplayName("S6-07: Chuyến đã kết thúc không kết thúc lại được")
    void complete_twice_conflict() {
        DeliveryTrip t = trip(NOW.plusHours(1));
        t.setStatus(DeliveryTrip.STATUS_COMPLETED);
        when(tripRepository.findByIdForUpdate(500L)).thenReturn(Optional.of(t));
        when(stockLedgerService.allowedWarehouseIds(whManager)).thenReturn(null);

        assertThatThrownBy(() -> service.complete(500L, null, whManager))
                .hasFieldOrPropertyWithValue("code", "TRIP_ALREADY_COMPLETED");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-07: Đơn trên chuyến đang chạy -> onActiveTrip = true; chuyến đã kết thúc -> false")
    void onActiveTrip_rule() {
        SalesOrder o = order(1, SalesOrder.STATUS_DISPATCHED, hn);
        assertThat(DeliveryTripService.onActiveTrip(o)).isFalse();
        DeliveryTrip t = trip(NOW.plusHours(1));
        o.setDeliveryTrip(t);
        assertThat(DeliveryTripService.onActiveTrip(o)).isTrue();
        t.setStatus(DeliveryTrip.STATUS_COMPLETED);
        assertThat(DeliveryTripService.onActiveTrip(o)).isFalse();
    }
}
