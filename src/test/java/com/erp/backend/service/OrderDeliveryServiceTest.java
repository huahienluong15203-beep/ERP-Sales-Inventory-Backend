package com.erp.backend.service;

import com.erp.backend.dto.order.OrderDeliveryConfirmRequest;
import com.erp.backend.dto.order.OrderDeliveryTimelineResponse;
import com.erp.backend.dto.order.OrderLineDeliveryRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import org.springframework.http.HttpStatus;
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
import org.springframework.mock.web.MockMultipartFile;

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

/** S6-08: Xác nhận giao hàng, ảnh biên bản, giao không thành công nhập lại kho. */
@ExtendWith(MockitoExtension.class)
class OrderDeliveryServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private com.erp.backend.repository.CustomerRepository customerRepository;
    @Mock private com.erp.backend.repository.UserRepository userRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AuditLogService auditLogService;
    @Mock private OrderDraftService orderDraftService;
    @Mock private DeliveryProofStorageService proofStorage;

    @InjectMocks private OrderDeliveryService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 15, 16, 0);
    private static final LocalDateTime DISPATCHED_AT = LocalDateTime.of(2026, 10, 15, 8, 0);

    private final UserDetailsImpl whManager = actor(30, "ROLE_WH_MANAGER");
    private final Product coca = Product.builder().id(10L).sku("SP-COCA").name("Coca").baseUnit("Lon").build();
    private final Product pepsi = Product.builder().id(11L).sku("SP-PEPSI").name("Pepsi").baseUnit("Lon").build();

    private SalesOrder order;
    private SalesOrderLine cocaLine;
    private SalesOrderLine pepsiLine;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
        order = SalesOrder.builder().id(100L).code("DH261015-AB12").customer(customer(6L, salesRep(7L)))
                .status(SalesOrder.STATUS_DISPATCHED).dispatchedAt(DISPATCHED_AT)
                .reservationStatus(SalesOrder.RESERVATION_DISPATCHED)
                .deliveryProofFile("order_100_1_abc.jpg")
                .lines(new ArrayList<>()).build();
        cocaLine = SalesOrderLine.builder().id(1L).lineNo(1).product(coca).productSku("SP-COCA").productName("Coca")
                .unitName("Thùng").conversionFactor(new BigDecimal("24")).quantity(new BigDecimal("10"))
                .baseUnit("Lon").baseQuantity(new BigDecimal("240")).build();
        pepsiLine = SalesOrderLine.builder().id(2L).lineNo(2).product(pepsi).productSku("SP-PEPSI").productName("Pepsi")
                .unitName("Lon").conversionFactor(BigDecimal.ONE).quantity(new BigDecimal("50"))
                .baseUnit("Lon").baseQuantity(new BigDecimal("50")).build();
        order.addLine(cocaLine);
        order.addLine(pepsiLine);
    }

    private void stubOrder() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
    }

    private void stubSave() {
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(i -> i.getArgument(0));
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(mock(OrderResponse.class));
    }

    @SuppressWarnings("unchecked")
    private List<InventoryService.DispatchLine> capturedReturns() {
        ArgumentCaptor<List<InventoryService.DispatchLine>> c = ArgumentCaptor.forClass(List.class);
        verify(inventoryService).returnDeliveredStock(eq(order), c.capture(), anyString(), eq(whManager));
        return c.getValue();
    }

    private static OrderLineDeliveryRequest delivered(long lineId, String qty, String reason) {
        return OrderLineDeliveryRequest.builder().lineId(lineId).deliveredQuantity(new BigDecimal(qty))
                .shortageReason(reason).build();
    }

    // ======================= GIAO THÀNH CÔNG =======================

    @Test
    @DisplayName("S6-08: Giao đủ -> Đã giao, lưu người nhận / giờ giao / người xác nhận, không nhập lại kho")
    void confirm_full_success() {
        stubOrder();
        stubSave();

        service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("  Anh Tuấn  ")
                .deliveredAt(LocalDateTime.of(2026, 10, 15, 15, 30)).build(), whManager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERED);
        assertThat(order.getReceiverName()).isEqualTo("Anh Tuấn");
        assertThat(order.getDeliveredAt()).isEqualTo(LocalDateTime.of(2026, 10, 15, 15, 30));
        assertThat(order.getDeliveryConfirmedAt()).isEqualTo(NOW);
        assertThat(order.getDeliveryConfirmedByUsername()).isEqualTo(whManager.getUsername());
        assertThat(cocaLine.getDeliveredQuantity()).isEqualByComparingTo("10");
        assertThat(pepsiLine.getDeliveredQuantity()).isEqualByComparingTo("50");
        assertThat(capturedReturns()).isEmpty();
        ArgumentCaptor<SalesOrderApproval> h = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(h.capture());
        assertThat(h.getValue().getToStatus()).isEqualTo(SalesOrder.STATUS_DELIVERED);
        assertThat(h.getValue().getComment()).contains("Anh Tuấn");
        verify(auditLogService).record(eq(AuditModule.ORDER), eq("CONFIRM_DELIVERY"), eq("SALES_ORDER"), eq(100L),
                eq("DH261015-AB12"), anyString(), contains("SP-COCA=10"), anyString(), eq(whManager));
    }

    @Test
    @DisplayName("S6-08: Giao thiếu 8/10 thùng -> phần thiếu 48 lon nhập lại kho, lý do lấy từ dòng")
    void confirm_short_returnsUndeliveredToStock() {
        stubOrder();
        stubSave();

        service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("Chị Lan")
                .lineDeliveries(List.of(delivered(1L, "8", "Móp 2 thùng"))).build(), whManager);

        assertThat(cocaLine.getDeliveredQuantity()).isEqualByComparingTo("8");
        assertThat(cocaLine.getShortageReason()).isEqualTo("Móp 2 thùng");
        assertThat(order.getDeliveredAt()).isEqualTo(NOW);
        List<InventoryService.DispatchLine> r = capturedReturns();
        assertThat(r).hasSize(1);
        assertThat(r.get(0).line()).isSameAs(cocaLine);
        assertThat(r.get(0).baseQuantity()).isEqualByComparingTo("48");
    }

    @Test
    @DisplayName("S6-08: Chưa tải ảnh biên bản -> 409, không đổi trạng thái, không đụng tồn")
    void confirm_withoutProof_conflict() {
        order.setDeliveryProofFile(null);
        stubOrder();

        assertThatThrownBy(() -> service.confirmDelivery(100L,
                OrderDeliveryConfirmRequest.builder().receiverName("A").build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_PROOF_REQUIRED");
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DISPATCHED);
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-08: Thiếu tên người nhận -> 400 (chưa khoá đơn)")
    void confirm_withoutReceiver_badRequest() {
        assertThatThrownBy(() -> service.confirmDelivery(100L,
                OrderDeliveryConfirmRequest.builder().receiverName("   ").build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "RECEIVER_REQUIRED");
        verifyNoInteractions(orderRepository);
    }

    @Test
    @DisplayName("S6-08: Giao thiếu mà không có lý do -> 400, không nhập lại kho")
    void confirm_shortWithoutReason_badRequest() {
        stubOrder();

        assertThatThrownBy(() -> service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("A")
                .lineDeliveries(List.of(delivered(2L, "40", null))).build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_SHORTAGE_REASON_REQUIRED");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-08: Thời điểm giao ở tương lai / trước lúc xuất kho -> 400")
    void confirm_invalidDeliveredAt_badRequest() {
        stubOrder();

        assertThatThrownBy(() -> service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("A")
                .deliveredAt(NOW.plusHours(2)).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "DELIVERED_AT_IN_FUTURE");
        assertThatThrownBy(() -> service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("A")
                .deliveredAt(DISPATCHED_AT.minusHours(1)).build(), whManager))
                .hasFieldOrPropertyWithValue("code", "DELIVERED_BEFORE_DISPATCH");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-08: Đại lý không nhận dòng nào -> 409, gợi ý ghi nhận Giao không thành công")
    void confirm_nothingDelivered_conflict() {
        stubOrder();

        assertThatThrownBy(() -> service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("A")
                .note("Đại lý đóng cửa")
                .lineDeliveries(List.of(delivered(1L, "0", null), delivered(2L, "0", null))).build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOTHING_DELIVERED");
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-08: Đơn chưa xuất kho / đã giao rồi -> 409 (đã xác nhận thì không sửa)")
    void confirm_wrongStatus_conflict() {
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        stubOrder();

        assertThatThrownBy(() -> service.confirmDelivery(100L,
                OrderDeliveryConfirmRequest.builder().receiverName("A").build(), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_NOT_ALLOWED");
    }

    @Test
    @DisplayName("S6-08: Kế toán / đại lý không ghi nhận giao hàng được -> 403")
    void confirm_wrongRole_forbidden() {
        assertThatThrownBy(() -> service.confirmDelivery(100L,
                OrderDeliveryConfirmRequest.builder().receiverName("A").build(), actor(40, "ROLE_ACCOUNTANT")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STATUS_CHANGE_NOT_ALLOWED");
        assertThatThrownBy(() -> service.markFailed(100L, "x", actor(41, "ROLE_CUSTOMER")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "STATUS_CHANGE_NOT_ALLOWED");
        verifyNoInteractions(orderRepository);
    }

    // ======================= GIAO KHÔNG THÀNH CÔNG =======================

    @Test
    @DisplayName("S6-08: Giao không thành công -> toàn bộ hàng nhập lại kho, đơn DELIVERY_FAILED kèm lý do")
    void markFailed_returnsAllStock() {
        stubOrder();
        stubSave();

        service.markFailed(100L, "  Đại lý đóng cửa  ", whManager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERY_FAILED);
        assertThat(order.getDeliveryFailedReason()).isEqualTo("Đại lý đóng cửa");
        assertThat(order.getDeliveryFailedAt()).isEqualTo(NOW);
        assertThat(cocaLine.getDeliveredQuantity()).isEqualByComparingTo("0");
        List<InventoryService.DispatchLine> r = capturedReturns();
        assertThat(r).hasSize(2);
        assertThat(r.get(0).baseQuantity()).isEqualByComparingTo("240");
        assertThat(r.get(1).baseQuantity()).isEqualByComparingTo("50");
        verify(auditLogService).record(eq(AuditModule.ORDER), eq("DELIVERY_FAILED"), eq("SALES_ORDER"), eq(100L),
                eq("DH261015-AB12"), anyString(), anyString(), eq("Đại lý đóng cửa"), eq(whManager));
    }

    @Test
    @DisplayName("S6-08: Giao không thành công mà không có lý do -> 400")
    void markFailed_withoutReason_badRequest() {
        assertThatThrownBy(() -> service.markFailed(100L, "  ", whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "REASON_REQUIRED");
        verifyNoInteractions(orderRepository, inventoryService);
    }

    // ======================= ẢNH BIÊN BẢN =======================

    @Test
    @DisplayName("S6-08: Tải ảnh biên bản mới -> lưu tên tệp, ghi nhật ký, xoá ảnh cũ")
    void uploadProof_replacesOldFile() {
        stubOrder();
        stubSave();
        MockMultipartFile file = new MockMultipartFile("file", "bb.jpg", "image/jpeg", new byte[]{1, 2, 3});
        when(proofStorage.store(100L, file)).thenReturn(new DeliveryProofStorageService.StoredFile("order_100_2_def.jpg", "image/jpeg"));

        service.uploadProof(100L, file, whManager);

        assertThat(order.getDeliveryProofFile()).isEqualTo("order_100_2_def.jpg");
        assertThat(order.getDeliveryProofContentType()).isEqualTo("image/jpeg");
        assertThat(order.getDeliveryProofUploadedAt()).isEqualTo(NOW);
        // Không có transaction trong unit test -> AfterCommit chạy ngay
        verify(proofStorage).deleteQuietly("order_100_1_abc.jpg");
        verify(auditLogService).record(eq(AuditModule.ORDER), eq("UPLOAD_DELIVERY_PROOF"), eq("SALES_ORDER"), eq(100L),
                eq("DH261015-AB12"), eq("order_100_1_abc.jpg"), eq("order_100_2_def.jpg"), anyString(), eq(whManager));
    }

    @Test
    @DisplayName("S6-08: Đơn đã giao xong thì không thay ảnh biên bản được")
    void uploadProof_afterDelivered_conflict() {
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        stubOrder();

        assertThatThrownBy(() -> service.uploadProof(100L,
                new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1}), whManager))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "DELIVERY_NOT_ALLOWED");
        verifyNoInteractions(proofStorage);
    }

    @Test
    @DisplayName("S6-08: NV kinh doanh xem ảnh biên bản đơn của đại lý người khác -> 404")
    void loadProof_otherRepCustomer_hidden() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.loadProof(100L, actor(9, "ROLE_SALES_REP")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "CUSTOMER_NOT_FOUND");
        verifyNoInteractions(proofStorage);
    }

    @Test
    @DisplayName("S6-08: Kế toán xem được ảnh biên bản")
    void loadProof_accountant_ok() {
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        DeliveryProofStorageService.LoadedFile f = new DeliveryProofStorageService.LoadedFile(new byte[]{9}, "image/jpeg");
        when(proofStorage.load("order_100_1_abc.jpg")).thenReturn(f);

        assertThat(service.loadProof(100L, actor(40, "ROLE_ACCOUNTANT"))).isSameAs(f);
    }

    @Test
    @DisplayName("S6-08: Số chưa giao quy về ĐVT cơ sở: giao 8/10 thùng x24 -> 48 lon; giao 0 -> cả dòng")
    void undeliveredBase_converts() {
        cocaLine.setDeliveredQuantity(new BigDecimal("8"));
        assertThat(OrderDeliveryService.undeliveredBase(cocaLine)).isEqualByComparingTo("48");
        cocaLine.setDeliveredQuantity(BigDecimal.ZERO);
        assertThat(OrderDeliveryService.undeliveredBase(cocaLine)).isEqualByComparingTo("240");
    }

    // ======================= S6-07: ĐƠN TRÊN CHUYẾN GIAO =======================

    private DeliveryTrip runningTrip() {
        return DeliveryTrip.builder().id(500L).code("CG261015-AAAA").driverName("Anh Tuấn")
                .status(DeliveryTrip.STATUS_IN_PROGRESS).departedAt(DISPATCHED_AT).expectedReturnAt(NOW.plusHours(2)).build();
    }

    @Test
    @DisplayName("S6-07: Đơn trên chuyến đang chạy giao thiếu -> hàng thiếu vẫn thuộc chuyến, CHƯA nhập lại kho")
    void confirm_short_onActiveTrip_deferred() {
        order.setDeliveryTrip(runningTrip());
        stubOrder();
        stubSave();

        service.confirmDelivery(100L, OrderDeliveryConfirmRequest.builder().receiverName("Chị Lan")
                .lineDeliveries(List.of(delivered(1L, "8", "Móp 2 thùng"))).build(), whManager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERED);
        assertThat(order.getStockReturnedAt()).isNull();
        verifyNoInteractions(inventoryService);
        ArgumentCaptor<SalesOrderApproval> h = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(h.capture());
        assertThat(h.getValue().getComment()).contains("CG261015-AAAA");
    }

    @Test
    @DisplayName("S6-07: Đơn trên chuyến đang chạy giao không thành công -> chưa nhập lại kho (chờ kết thúc chuyến)")
    void markFailed_onActiveTrip_deferred() {
        order.setDeliveryTrip(runningTrip());
        stubOrder();
        stubSave();

        service.markFailed(100L, "Đại lý đóng cửa", whManager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERY_FAILED);
        assertThat(order.getStockReturnedAt()).isNull();
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S6-07: Đơn không trên chuyến (hoặc chuyến đã kết thúc) -> nhập lại kho ngay như cũ, ghi thời điểm")
    void markFailed_notOnTrip_returnsNow() {
        DeliveryTrip done = runningTrip();
        done.setStatus(DeliveryTrip.STATUS_COMPLETED);
        order.setDeliveryTrip(done);
        stubOrder();
        stubSave();

        service.markFailed(100L, "Đại lý đóng cửa", whManager);

        assertThat(capturedReturns()).hasSize(2);
        assertThat(order.getStockReturnedAt()).isEqualTo(NOW);
    }

    // ======================= S6-09: TIMELINE & THỰC GIAO =======================

    @Test
    @DisplayName("S6-09: Không có lịch sử chuyển sang soạn hàng -> mốc 'Đang soạn' để trống, không bịa giờ duyệt + 15 phút")
    void getDeliveryTimeline_noPickingHistory_timeIsNull() {
        order.setStatus(SalesOrder.STATUS_PICKING);
        order.setApprovedAt(LocalDateTime.of(2026, 10, 15, 8, 30));
        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of());

        OrderDeliveryTimelineResponse timeline = service.getDeliveryTimeline(100L, whManager);

        assertThat(timeline.steps().picking().current()).isTrue();
        assertThat(timeline.steps().picking().time()).isNull();
    }

    @Test
    @DisplayName("S6-09 / SCRUM-258: Lấy delivery timeline thành công đủ 5 mốc & chi tiết số lượng thực giao từng dòng")
    void getDeliveryTimeline_success_allFiveMilestones() {
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        order.setApprovedAt(LocalDateTime.of(2026, 10, 15, 8, 30));
        order.setApprovedByUsername("approver_user");
        order.setDispatchedAt(LocalDateTime.of(2026, 10, 15, 10, 0));
        order.setDispatchedByUsername("dispatcher_user");
        order.setDispatchNote("Giao trước 17h");
        order.setReceiverName("Đại diện nhận hàng");
        order.setDeliveredAt(LocalDateTime.of(2026, 10, 15, 14, 30));
        order.setDeliveryConfirmedAt(LocalDateTime.of(2026, 10, 15, 14, 35));
        order.setDeliveryConfirmedByUsername("confirmer_user");

        DeliveryTrip trip = DeliveryTrip.builder()
                .id(55L)
                .code("CH-001")
                .vehiclePlate("29C-12345")
                .driverName("Tài xế giao nhận")
                .driverPhone("0912345678")
                .status(DeliveryTrip.STATUS_COMPLETED)
                .departedAt(LocalDateTime.of(2026, 10, 15, 10, 30))
                .completedAt(LocalDateTime.of(2026, 10, 15, 15, 0))
                .build();
        order.setDeliveryTrip(trip);

        // cocaLine: ordered 10, delivered 8 (thiếu 2)
        cocaLine.setQuantity(new BigDecimal("10"));
        cocaLine.setDeliveredQuantity(new BigDecimal("8"));
        cocaLine.setShortageReason("Hỏng vỡ khi bốc dỡ");

        // pepsiLine: ordered 50, delivered 50 (đủ)
        pepsiLine.setQuantity(new BigDecimal("50"));
        pepsiLine.setDeliveredQuantity(new BigDecimal("50"));

        SalesOrderApproval approvalPicking = SalesOrderApproval.builder()
                .id(1L)
                .order(order)
                .fromStatus(SalesOrder.STATUS_APPROVED)
                .toStatus(SalesOrder.STATUS_PICKING)
                .createdAt(LocalDateTime.of(2026, 10, 15, 9, 0))
                .comment("Bắt đầu soạn hàng")
                .build();

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of(approvalPicking));

        OrderDeliveryTimelineResponse timeline = service.getDeliveryTimeline(100L, whManager);

        assertThat(timeline).isNotNull();
        assertThat(timeline.orderId()).isEqualTo(100L);
        assertThat(timeline.orderCode()).isEqualTo("DH261015-AB12");
        assertThat(timeline.status()).isEqualTo(SalesOrder.STATUS_DELIVERED);
        assertThat(timeline.currentStep()).isEqualTo(5);

        // Kiểm tra 5 mốc thời gian (AC1)
        OrderDeliveryTimelineResponse.TimelineSteps steps = timeline.steps();
        assertThat(steps).isNotNull();

        // Mốc 1: ĐÃ DUYỆT
        assertThat(steps.approved().statusKey()).isEqualTo("APPROVED");
        assertThat(steps.approved().time()).isEqualTo(LocalDateTime.of(2026, 10, 15, 8, 30));
        assertThat(steps.approved().completed()).isTrue();

        // Mốc 2: ĐANG SOẠN
        assertThat(steps.picking().statusKey()).isEqualTo("PICKING");
        assertThat(steps.picking().time()).isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0));
        assertThat(steps.picking().completed()).isTrue();

        // Mốc 3: ĐÃ XUẤT
        assertThat(steps.dispatched().statusKey()).isEqualTo("DISPATCHED");
        assertThat(steps.dispatched().time()).isEqualTo(LocalDateTime.of(2026, 10, 15, 10, 0));
        assertThat(steps.dispatched().completed()).isTrue();

        // Mốc 4: ĐANG GIAO
        assertThat(steps.shipping().statusKey()).isEqualTo("SHIPPING");
        assertThat(steps.shipping().completed()).isTrue();

        // Mốc 5: ĐÃ GIAO
        assertThat(steps.delivered().statusKey()).isEqualTo("DELIVERED");
        assertThat(steps.delivered().time()).isEqualTo(LocalDateTime.of(2026, 10, 15, 14, 30));
        assertThat(steps.delivered().completed()).isTrue();
        assertThat(steps.delivered().current()).isTrue();

        // Kiểm tra thông tin chuyến xe
        assertThat(timeline.tripInfo()).isNotNull();
        assertThat(timeline.tripInfo().tripCode()).isEqualTo("CH-001");
        assertThat(timeline.tripInfo().vehiclePlate()).isEqualTo("29C-12345");
        assertThat(timeline.tripInfo().driverName()).isEqualTo("Tài xế giao nhận");
        assertThat(timeline.tripInfo().driverPhone()).isEqualTo("0912345678");

        // Kiểm tra số lượng thực giao từng dòng hàng (AC2)
        List<OrderDeliveryTimelineResponse.OrderDeliveryLineItem> lines = timeline.lines();
        assertThat(lines).hasSize(2);

        OrderDeliveryTimelineResponse.OrderDeliveryLineItem l1 = lines.get(0);
        assertThat(l1.productSku()).isEqualTo("SP-COCA");
        assertThat(l1.orderedQuantity()).isEqualByComparingTo("10");
        assertThat(l1.deliveredQuantity()).isEqualByComparingTo("8");
        assertThat(l1.shortageQuantity()).isEqualByComparingTo("2");
        assertThat(l1.isShortage()).isTrue();
        assertThat(l1.shortageReason()).isEqualTo("Hỏng vỡ khi bốc dỡ");

        OrderDeliveryTimelineResponse.OrderDeliveryLineItem l2 = lines.get(1);
        assertThat(l2.productSku()).isEqualTo("SP-PEPSI");
        assertThat(l2.orderedQuantity()).isEqualByComparingTo("50");
        assertThat(l2.deliveredQuantity()).isEqualByComparingTo("50");
        assertThat(l2.shortageQuantity()).isEqualByComparingTo("0");
        assertThat(l2.isShortage()).isFalse();
    }

    @Test
    @DisplayName("SCRUM-233 / S6-09: Chặn user lạ xem đơn (Customer B xem đơn của Customer A -> ném 404 NOT_FOUND)")
    void getDeliveryTimeline_accessDenied_strangerCustomer_throwsNotFound() {
        // Customer A là chủ sở hữu của order (id 6L)
        // Actor là Customer B (khách lạ, userId 99L, customerId 999L)
        UserDetailsImpl strangerCustomer = actor(99L, "ROLE_CUSTOMER");
        Customer customerB = Customer.builder().id(999L).code("DL-999").name("Đại lý 999").build();

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(customerRepository.findByPortalUser_Id(99L)).thenReturn(Optional.of(customerB));

        assertThatThrownBy(() -> service.getDeliveryTimeline(100L, strangerCustomer))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(be.getMessage()).contains("Không tìm thấy");
                });
    }

    @Test
    @DisplayName("SCRUM-233 / S6-09: Chặn nhân viên kinh doanh không phụ trách xem đơn -> ném 404 NOT_FOUND")
    void getDeliveryTimeline_accessDenied_unassignedSalesRep_throwsNotFound() {
        // Sales Rep phụ trách order là ID 7, actor là Sales Rep ID 88
        UserDetailsImpl unassignedSales = actor(88L, "ROLE_SALES_REP");

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.getDeliveryTimeline(100L, unassignedSales))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(be.getMessage()).contains("Không tìm thấy");
                });
    }

    @Test
    @DisplayName("SCRUM-233 / S6-09: Nhân viên kinh doanh được phân công phụ trách được phép xem timeline")
    void getDeliveryTimeline_accessAllowed_assignedSalesRep() {
        // Sales Rep phụ trách order là ID 7
        UserDetailsImpl assignedSales = actor(7L, "ROLE_SALES_REP");

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of());

        OrderDeliveryTimelineResponse timeline = service.getDeliveryTimeline(100L, assignedSales);
        assertThat(timeline).isNotNull();
        assertThat(timeline.orderId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("SCRUM-258 / S6-09: Đại lý chính chủ xem đơn qua tài khoản portal thành công")
    void getDeliveryTimeline_accessAllowed_portalCustomerOwner() {
        UserDetailsImpl dealerActor = actor(50L, "ROLE_CUSTOMER");
        Customer customerA = order.getCustomer();
        customerA.setPortalUser(User.builder().id(50L).build());

        when(orderRepository.findById(100L)).thenReturn(Optional.of(order));
        when(customerRepository.findByPortalUser_Id(50L)).thenReturn(Optional.of(customerA));
        when(approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(100L)).thenReturn(List.of());

        OrderDeliveryTimelineResponse timeline = service.getDeliveryTimeline(100L, dealerActor);
        assertThat(timeline).isNotNull();
        assertThat(timeline.orderId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("S6-09: Đơn hàng không tồn tại -> ném 404 NOT_FOUND")
    void getDeliveryTimeline_orderNotFound_throwsNotFound() {
        when(orderRepository.findById(9999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDeliveryTimeline(9999L, whManager))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                });
    }
}
