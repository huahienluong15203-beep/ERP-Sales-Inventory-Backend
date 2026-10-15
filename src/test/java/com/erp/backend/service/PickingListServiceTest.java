package com.erp.backend.service;

import com.erp.backend.dto.inventory.picking.*;
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
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static com.erp.backend.service.CustomerTestData.actor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test PickingListService - S6-03 Gom đơn, gộp mặt hàng, sắp xếp đường đi kho")
class PickingListServiceTest {

    @Mock private PickingListRepository pickingListRepository;
    @Mock private PickingListItemRepository pickingListItemRepository;
    @Mock private SalesOrderRepository salesOrderRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private WarehouseLocationRepository locationRepository;
    @Mock private ProductLotRepository productLotRepository;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private PickingListService service;

    private final UserDetailsImpl staff = actor(10, "ROLE_WAREHOUSE");
    private final UserDetailsImpl manager = actor(11, "ROLE_WH_MANAGER");

    private Warehouse whHn;
    private WarehouseLocation locA1;
    private WarehouseLocation locB1;
    private Product pepsi;
    private Product coca;
    private SalesOrder order1;
    private SalesOrder order2;

    @BeforeEach
    void setUp() {
        whHn = Warehouse.builder().id(1L).code("WH-MB01").name("Kho Tổng Miền Bắc").status("ACTIVE").build();

        locA1 = WarehouseLocation.builder().id(101L).warehouse(whHn).code("BIN-A1-01").name("Ô A1-01")
                .locationType("BIN").zone("Khu A").shelf("Kệ 01").status("ACTIVE").build();

        locB1 = WarehouseLocation.builder().id(102L).warehouse(whHn).code("BIN-B1-01").name("Ô B1-01")
                .locationType("BIN").zone("Khu B").shelf("Kệ 02").status("ACTIVE").build();

        pepsi = Product.builder().id(21L).sku("SP-PEPSI").name("Pepsi 330ml").baseUnit("Lon")
                .defaultLocation(locA1).hasBatchManagement(false).status("ACTIVE").build();

        coca = Product.builder().id(22L).sku("SP-COCA").name("Coca 330ml").baseUnit("Lon")
                .defaultLocation(locB1).hasBatchManagement(false).status("ACTIVE").build();

        // Đơn 1: 10 lon Pepsi + 5 lon Coca
        order1 = SalesOrder.builder().id(301L).code("SO-001").status(SalesOrder.STATUS_APPROVED)
                .reservedWarehouse(whHn).totalAmount(new BigDecimal("150000")).lines(new ArrayList<>()).build();
        SalesOrderLine line1 = SalesOrderLine.builder().id(1L).order(order1).product(pepsi).productSku(pepsi.getSku())
                .productName(pepsi.getName()).baseQuantity(new BigDecimal("10")).quantity(new BigDecimal("10")).build();
        SalesOrderLine line2 = SalesOrderLine.builder().id(2L).order(order1).product(coca).productSku(coca.getSku())
                .productName(coca.getName()).baseQuantity(new BigDecimal("5")).quantity(new BigDecimal("5")).build();
        order1.getLines().add(line1);
        order1.getLines().add(line2);

        // Đơn 2: 15 lon Pepsi
        order2 = SalesOrder.builder().id(302L).code("SO-002").status(SalesOrder.STATUS_APPROVED)
                .reservedWarehouse(whHn).totalAmount(new BigDecimal("120000")).lines(new ArrayList<>()).build();
        SalesOrderLine line3 = SalesOrderLine.builder().id(3L).order(order2).product(pepsi).productSku(pepsi.getSku())
                .productName(pepsi.getName()).baseQuantity(new BigDecimal("15")).quantity(new BigDecimal("15")).build();
        order2.getLines().add(line3);

        lenient().when(pickingListRepository.save(any(PickingList.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(salesOrderRepository.save(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S6-03: Lấy danh sách đơn hàng đã duyệt tại kho chờ gom đợt soạn")
    void getAvailableOrdersForPicking_returnsOnlyFreeApprovedOrders() {
        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(whHn));
        when(salesOrderRepository.findAll(any(Specification.class), any(Sort.class)))
                .thenReturn(List.of(order1, order2));
        when(pickingListRepository.findOrderIdsInActivePickingList(any(), any()))
                .thenReturn(List.of(302L)); // order2 đã trong đợt soạn khác

        List<AvailablePickingOrderResponse> available = service.getAvailableOrdersForPicking(1L, manager);

        assertThat(available).hasSize(1);
        assertThat(available.get(0).getOrderId()).isEqualTo(301L);
        assertThat(available.get(0).getOrderCode()).isEqualTo("SO-001");
    }

    @Test
    @DisplayName("S6-03: Xem trước gom đơn (Preview): gộp 2 đơn có chung Pepsi thành 25 lon và sắp xếp đường đi kho")
    void previewPickingList_aggregatesItemsAndSortsByRoute() {
        PickingListPreviewRequest req = PickingListPreviewRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of(301L, 302L))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(whHn));
        when(salesOrderRepository.findAllById(List.of(301L, 302L))).thenReturn(List.of(order1, order2));
        when(locationRepository.findByWarehouse_IdOrderByCodeAsc(1L)).thenReturn(List.of(locA1, locB1));

        PickingListPreviewResponse preview = service.previewPickingList(req, manager);

        assertThat(preview.getTotalOrders()).isEqualTo(2);
        assertThat(preview.getTotalItems()).isEqualTo(2); // 2 mặt hàng (Pepsi và Coca)
        assertThat(preview.getTotalQuantity()).isEqualByComparingTo("30"); // 10 + 5 + 15 = 30

        // Thứ tự đường đi: BIN-A1-01 (Pepsi) trước BIN-B1-01 (Coca)
        assertThat(preview.getItems().get(0).getProductSku()).isEqualTo("SP-PEPSI");
        assertThat(preview.getItems().get(0).getRequestedQuantity()).isEqualByComparingTo("25"); // 10 + 15
        assertThat(preview.getItems().get(0).getRouteSequence()).isEqualTo(1);
        assertThat(preview.getItems().get(0).getLocationCode()).isEqualTo("BIN-A1-01");
        assertThat(preview.getItems().get(0).getAllocations()).hasSize(2);

        assertThat(preview.getItems().get(1).getProductSku()).isEqualTo("SP-COCA");
        assertThat(preview.getItems().get(1).getRequestedQuantity()).isEqualByComparingTo("5");
        assertThat(preview.getItems().get(1).getRouteSequence()).isEqualTo(2);
        assertThat(preview.getItems().get(1).getLocationCode()).isEqualTo("BIN-B1-01");
    }

    @Test
    @DisplayName("S6-03: Tạo phiếu soạn hàng thành công, chuyển trạng thái đơn hàng sang PICKING")
    void createPickingList_success() {
        PickingListCreateRequest req = PickingListCreateRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of(301L, 302L))
                .note("Đợt soạn buổi chiều")
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(whHn));
        when(salesOrderRepository.findAllById(List.of(301L, 302L))).thenReturn(List.of(order1, order2));
        when(pickingListRepository.findOrderIdsInActivePickingList(any(), any())).thenReturn(Collections.emptyList());
        when(locationRepository.findByWarehouse_IdOrderByCodeAsc(1L)).thenReturn(List.of(locA1, locB1));

        PickingListResponse res = service.createPickingList(req, manager);

        assertThat(res).isNotNull();
        assertThat(res.getCode()).startsWith("PKL-");
        assertThat(res.getStatus()).isEqualTo(PickingList.STATUS_ASSIGNED);
        assertThat(res.getTotalOrders()).isEqualTo(2);
        assertThat(res.getTotalItems()).isEqualTo(2);
        assertThat(res.getTotalRequestedQuantity()).isEqualByComparingTo("30");

        // Kiểm tra các đơn hàng đã được cập nhật sang trạng thái PICKING
        assertThat(order1.getStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
        assertThat(order2.getStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
    }

    @Test
    @DisplayName("S6-03: Tạo phiếu bị xung đột 409 nếu đơn đã nằm trong đợt soạn khác đang chạy")
    void createPickingList_orderAlreadyInActivePicking_throwsConflict() {
        PickingListCreateRequest req = PickingListCreateRequest.builder()
                .warehouseId(1L)
                .orderIds(List.of(301L))
                .build();

        when(warehouseRepository.findById(1L)).thenReturn(Optional.of(whHn));
        when(salesOrderRepository.findAllById(List.of(301L))).thenReturn(List.of(order1));
        when(pickingListRepository.findOrderIdsInActivePickingList(any(), any())).thenReturn(List.of(301L));

        assertThatThrownBy(() -> service.createPickingList(req, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("đã nằm trong một đợt soạn hàng khác");
    }

    @Test
    @DisplayName("S6-03: Soạn thiếu mà không nhập lý do -> lỗi 400 Bad Request")
    void updateItemProgress_shortageWithoutReason_throwsBadRequest() {
        PickingListItem item = PickingListItem.builder()
                .id(10L)
                .product(pepsi)
                .productSku(pepsi.getSku())
                .productName(pepsi.getName())
                .requestedQuantity(new BigDecimal("25"))
                .pickedQuantity(BigDecimal.ZERO)
                .build();

        PickingList pl = PickingList.builder().id(1L).warehouse(whHn).status(PickingList.STATUS_ASSIGNED)
                .items(new ArrayList<>(List.of(item))).build();
        item.setPickingList(pl);

        when(pickingListRepository.findById(1L)).thenReturn(Optional.of(pl));

        PickingItemUpdateRequest req = PickingItemUpdateRequest.builder()
                .pickedQuantity(new BigDecimal("20")) // Thiếu 5 lon
                .isPicked(true)
                .shortageReason(null) // Không có lý do
                .build();

        assertThatThrownBy(() -> service.updateItemProgress(1L, 10L, req, staff))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bắt buộc nhập lý do soạn thiếu");
    }

    @Test
    @DisplayName("S6-03: Soạn thiếu kèm lý do hợp lệ -> thành công và tự động phân bổ")
    void updateItemProgress_shortageWithValidReason_success() {
        PickingListItem item = PickingListItem.builder()
                .id(10L)
                .product(pepsi)
                .productSku(pepsi.getSku())
                .productName(pepsi.getName())
                .requestedQuantity(new BigDecimal("25"))
                .pickedQuantity(BigDecimal.ZERO)
                .allocations(new ArrayList<>())
                .build();

        PickingListItemAllocation alloc1 = PickingListItemAllocation.builder()
                .order(order1).orderCode("SO-001").requestedQuantity(new BigDecimal("10")).build();
        PickingListItemAllocation alloc2 = PickingListItemAllocation.builder()
                .order(order2).orderCode("SO-002").requestedQuantity(new BigDecimal("15")).build();
        item.addAllocation(alloc1);
        item.addAllocation(alloc2);

        PickingList pl = PickingList.builder().id(1L).warehouse(whHn).status(PickingList.STATUS_ASSIGNED)
                .items(new ArrayList<>(List.of(item))).build();
        item.setPickingList(pl);

        when(pickingListRepository.findById(1L)).thenReturn(Optional.of(pl));

        PickingItemUpdateRequest req = PickingItemUpdateRequest.builder()
                .pickedQuantity(new BigDecimal("20")) // Lấy được 20 (thiếu 5)
                .isPicked(true)
                .shortageReason("Hỏng bao bì 5 lon tại kệ A1")
                .build();

        PickingListResponse res = service.updateItemProgress(1L, 10L, req, staff);

        assertThat(res.getStatus()).isEqualTo(PickingList.STATUS_IN_PROGRESS);
        assertThat(item.getPickedQuantity()).isEqualByComparingTo("20");
        assertThat(item.getShortageReason()).isEqualTo("Hỏng bao bì 5 lon tại kệ A1");

        // Phân bổ: đơn 1 nhận đủ 10, đơn 2 nhận 10 (thiếu 5)
        assertThat(alloc1.getPickedQuantity()).isEqualByComparingTo("10");
        assertThat(alloc2.getPickedQuantity()).isEqualByComparingTo("10");
        assertThat(alloc2.getShortageReason()).isEqualTo("Hỏng bao bì 5 lon tại kệ A1");
    }

    @Test
    @DisplayName("S6-03: Chốt hoàn thành phiếu soạn hàng (COMPLETED) thành công")
    void completePickingList_success() {
        PickingListItem item = PickingListItem.builder().id(10L).product(pepsi).productSku("SP-PEPSI")
                .requestedQuantity(new BigDecimal("20")).pickedQuantity(new BigDecimal("20")).isPicked(true).build();

        PickingList pl = PickingList.builder().id(1L).warehouse(whHn).status(PickingList.STATUS_IN_PROGRESS)
                .totalRequestedQuantity(new BigDecimal("20")).totalPickedQuantity(new BigDecimal("20"))
                .items(new ArrayList<>(List.of(item))).build();

        when(pickingListRepository.findById(1L)).thenReturn(Optional.of(pl));

        PickingListResponse res = service.completePickingList(1L, staff);

        assertThat(res.getStatus()).isEqualTo(PickingList.STATUS_COMPLETED);
        assertThat(pl.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("S6-03: Hủy phiếu soạn hàng kèm lý do, tự động trả các đơn hàng về APPROVED")
    void cancelPickingList_revertsOrdersToApproved() {
        order1.setStatus(SalesOrder.STATUS_PICKING);
        PickingListOrder plo = PickingListOrder.builder().order(order1).orderCode("SO-001").build();

        PickingList pl = PickingList.builder().id(1L).warehouse(whHn).status(PickingList.STATUS_ASSIGNED)
                .orders(new ArrayList<>(List.of(plo))).build();

        when(pickingListRepository.findById(1L)).thenReturn(Optional.of(pl));

        PickingListResponse res = service.cancelPickingList(1L, "Khách hàng đổi địa chỉ giao hàng", manager);

        assertThat(res.getStatus()).isEqualTo(PickingList.STATUS_CANCELLED);
        assertThat(res.getCancelReason()).isEqualTo("Khách hàng đổi địa chỉ giao hàng");
        assertThat(order1.getStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
    }
}
