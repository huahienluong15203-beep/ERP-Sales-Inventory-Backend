package com.erp.backend.service;

import com.erp.backend.dto.order.OrderDeliveryConfirmRequest;
import com.erp.backend.dto.order.OrderLineDeliveryRequest;
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
}
