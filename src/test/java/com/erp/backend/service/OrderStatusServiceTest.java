package com.erp.backend.service;

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
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.actor;
import static com.erp.backend.service.CustomerTestData.customer;
import static com.erp.backend.service.CustomerTestData.salesRep;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderStatusServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private com.erp.backend.repository.CustomerRepository customerRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AuditLogService auditLogService;
    @Mock private OrderDraftService orderDraftService;

    @InjectMocks private OrderStatusService service;

    private final UserDetailsImpl rep = actor(7, "ROLE_SALES_REP");
    private final UserDetailsImpl manager = actor(3, "ROLE_SALES_MANAGER");
    private final UserDetailsImpl otherRep = actor(9, "ROLE_SALES_REP");

    private Customer cust;
    private SalesOrder order;

    @BeforeEach
    void setUp() {
        cust = customer(6L, salesRep(7L));
        order = SalesOrder.builder()
                .id(100L)
                .code("DH2610-00100")
                .customer(cust)
                .status(SalesOrder.STATUS_PENDING_APPROVAL)
                .totalAmount(new BigDecimal("15000000"))
                .lines(new ArrayList<>())
                .build();

        service.clock = Clock.fixed(
                LocalDate.of(2026, 10, 10).atStartOfDay(ZoneId.of("Asia/Ho_Chi_Minh")).toInstant(),
                ZoneId.of("Asia/Ho_Chi_Minh")
        );
    }

    // ============================ HUỶ ĐƠN HÀNG (S4-06 AC2 & AC3) ============================

    @Test
    @DisplayName("S4-06 AC2: Huỷ đơn Chờ duyệt (PENDING_APPROVAL) - Tự động nhả tồn đang giữ chỗ & ghi lịch sử")
    void cancel_whenPendingApproval_shouldReleaseStockAndSetCancelled() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        OrderResponse dummyResponse = mock(OrderResponse.class);
        when(orderDraftService.toResponse(any(SalesOrder.class))).thenReturn(dummyResponse);

        OrderResponse res = service.cancel(100L, "Đại lý yêu cầu đổi số lượng, huỷ đơn cũ", rep);

        assertThat(res).isNotNull();
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        assertThat(order.getCancelReason()).isEqualTo("Đại lý yêu cầu đổi số lượng, huỷ đơn cũ");
        assertThat(order.getLastApprovalComment()).isEqualTo("Đại lý yêu cầu đổi số lượng, huỷ đơn cũ");
        assertThat(order.getCancelledByUsername()).isEqualTo(rep.getUsername());
        assertThat(order.getCancelledAt()).isNotNull();

        // Kiểm tra nhả tồn đang giữ chỗ
        verify(inventoryService).releaseReservedStock(order);

        // Kiểm tra lưu lịch sử phê duyệt / hành trình
        ArgumentCaptor<SalesOrderApproval> approvalCaptor = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(approvalCaptor.capture());
        SalesOrderApproval approval = approvalCaptor.getValue();
        assertThat(approval.getAction()).isEqualTo(SalesOrderApproval.ACTION_CANCEL);
        assertThat(approval.getFromStatus()).isEqualTo(SalesOrder.STATUS_PENDING_APPROVAL);
        assertThat(approval.getToStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        assertThat(approval.getComment()).isEqualTo("Đại lý yêu cầu đổi số lượng, huỷ đơn cũ");

        // Kiểm tra ghi audit log
        verify(auditLogService).record(eq(AuditModule.INVOICE), eq("CANCEL_ORDER"), eq("SALES_ORDER"),
                eq(100L), eq("DH2610-00100"), eq(SalesOrder.STATUS_PENDING_APPROVAL),
                eq(SalesOrder.STATUS_CANCELLED), eq("Đại lý yêu cầu đổi số lượng, huỷ đơn cũ"), eq(rep));
    }

    @Test
    @DisplayName("S4-06 AC2: Huỷ đơn Đã duyệt (APPROVED) - Tự động nhả tồn đang giữ chỗ")
    void cancel_whenApproved_shouldReleaseStockAndSetCancelled() {
        order.setStatus(SalesOrder.STATUS_APPROVED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(100L, "Khách hết ngân sách tháng này", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        verify(inventoryService).releaseReservedStock(order);
    }

    @Test
    @DisplayName("S4-06 AC2: Huỷ đơn Đang soạn hàng (PICKING) - Tự động nhả tồn đang giữ chỗ")
    void cancel_whenPicking_shouldReleaseStockAndSetCancelled() {
        order.setStatus(SalesOrder.STATUS_PICKING);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(100L, "Đại lý báo hoãn nhập hàng", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        verify(inventoryService).releaseReservedStock(order);
    }

    @Test
    @DisplayName("S4-06 AC2: Huỷ đơn Nháp (DRAFT) - Không cần nhả tồn vì nháp chưa giữ chỗ")
    void cancel_whenDraft_shouldCancelWithoutReleasingStock() {
        order.setStatus(SalesOrder.STATUS_DRAFT);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancel(100L, "Lên nhầm đơn", rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        verify(inventoryService, never()).releaseReservedStock(any());
    }

    @Test
    @DisplayName("S4-06 AC3: Đơn đã xuất kho (DISPATCHED) - Nghiêm cấm huỷ, phải đi đường trả hàng")
    void cancel_whenDispatched_shouldThrowException() {
        order.setStatus(SalesOrder.STATUS_DISPATCHED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, "Muốn huỷ đơn", rep))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Đơn hàng đã xuất kho hoặc đã hoàn tất. Theo quy chuẩn S4-06 AC3, không thể hủy đơn mà phải làm thủ tục trả hàng");

        verify(inventoryService, never()).releaseReservedStock(any());
        verify(orderRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("S4-06 AC3: Đơn đã giao (DELIVERED) hoặc đã đóng (CLOSED) - Không thể huỷ")
    void cancel_whenDeliveredOrClosed_shouldThrowException() {
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, "Huỷ đơn", rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        order.setStatus(SalesOrder.STATUS_CLOSED);
        assertThatThrownBy(() -> service.cancel(100L, "Huỷ đơn", rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("S4-06 AC2: Bắt buộc nhập lý do huỷ đơn - Nếu bỏ trống hoặc khoảng trắng -> Báo lỗi")
    void cancel_whenBlankReason_shouldThrowBadRequest() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, "", rep))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bắt buộc nhập lý do hủy đơn hàng theo quy chuẩn S4-06 AC2");

        assertThatThrownBy(() -> service.cancel(100L, "   ", rep))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bắt buộc nhập lý do hủy đơn hàng theo quy chuẩn S4-06 AC2");

        assertThatThrownBy(() -> service.cancel(100L, null, rep))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Bắt buộc nhập lý do hủy đơn hàng theo quy chuẩn S4-06 AC2");
    }

    @Test
    @DisplayName("Huỷ đơn đã huỷ hoặc đã bị từ chối trước đó -> Báo lỗi xung đột")
    void cancel_whenAlreadyTerminated_shouldThrowConflict() {
        order.setStatus(SalesOrder.STATUS_CANCELLED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, "Huỷ tiếp", rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);

        order.setStatus(SalesOrder.STATUS_REJECTED);
        assertThatThrownBy(() -> service.cancel(100L, "Huỷ tiếp", rep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("Quyền truy cập: Sales Rep không phụ trách đại lý của đơn -> Chặn 404 Not Found")
    void cancel_whenOtherSalesRep_shouldThrowForbidden() {
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(100L, "Huỷ trộm", otherRep))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.NOT_FOUND);
    }

    // ============================ CHUYỂN TRẠNG THÁI VÒNG ĐỜI (SCRUM-158) ============================

    @Test
    @DisplayName("Vòng đời: Chuyển Đã duyệt (APPROVED) sang Đang soạn hàng (PICKING)")
    void transition_fromApprovedToPicking_success() {
        order.setStatus(SalesOrder.STATUS_APPROVED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transitionStatus(100L, "PICKING", "Kho bắt đầu nhặt hàng", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
        assertThat(order.getLastApprovalComment()).isEqualTo("Kho bắt đầu nhặt hàng");

        ArgumentCaptor<SalesOrderApproval> captor = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("PICKING");
        assertThat(captor.getValue().getFromStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        assertThat(captor.getValue().getToStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
    }

    @Test
    @DisplayName("Vòng đời: Chuyển Đang soạn hàng (PICKING) sang Đã xuất (DISPATCHED) - Gọi trừ tồn & giải phóng giữ chỗ")
    void transition_fromPickingToDispatched_shouldDispatchStockAndAdvanceStatus() {
        order.setStatus(SalesOrder.STATUS_PICKING);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transitionStatus(100L, "DISPATCHED", "Xe tải 29H-12345 rời kho", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DISPATCHED);
        verify(inventoryService).dispatchReservedStock(order);

        ArgumentCaptor<SalesOrderApproval> captor = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("DISPATCHED");
        assertThat(captor.getValue().getToStatus()).isEqualTo(SalesOrder.STATUS_DISPATCHED);
    }

    @Test
    @DisplayName("Vòng đời: Chuyển Đã xuất (DISPATCHED) sang Đã giao (DELIVERED)")
    void transition_fromDispatchedToDelivered_success() {
        order.setStatus(SalesOrder.STATUS_DISPATCHED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transitionStatus(100L, "DELIVERED", "Khách đã ký nhận biên bản", rep);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_DELIVERED);
    }

    @Test
    @DisplayName("Vòng đời: Chuyển Đã giao (DELIVERED) sang Đóng (CLOSED)")
    void transition_fromDeliveredToClosed_success() {
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transitionStatus(100L, "CLOSED", "Đơn hàng hoàn tất đối soát", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CLOSED);
    }

    @Test
    @DisplayName("Vòng đời: Chuyển sai luồng (APPROVED -> CLOSED hoặc đi lùi DISPATCHED -> PICKING) -> Báo lỗi")
    void transition_invalidFlow_shouldThrowBadRequest() {
        order.setStatus(SalesOrder.STATUS_APPROVED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.transitionStatus(100L, "CLOSED", null, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không thể chuyển trạng thái đơn hàng từ 'APPROVED' sang 'CLOSED'");

        order.setStatus(SalesOrder.STATUS_DISPATCHED);
        assertThatThrownBy(() -> service.transitionStatus(100L, "PICKING", null, manager))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Không thể chuyển trạng thái đơn hàng từ 'DISPATCHED' sang 'PICKING'");
    }

    @Test
    @DisplayName("Gọi transitionStatus với targetStatus CANCELLED -> Chuyển hướng sang cancel() hợp lệ")
    void transition_toCancelled_shouldDelegateToCancel() {
        order.setStatus(SalesOrder.STATUS_APPROVED);
        when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));

        service.transitionStatus(100L, "CANCELLED", "Hủy qua API status", manager);

        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        verify(inventoryService).releaseReservedStock(order);
    }
}
