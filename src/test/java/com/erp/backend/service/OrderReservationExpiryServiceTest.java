package com.erp.backend.service;

import com.erp.backend.config.OrderReservationExpiryJob;
import com.erp.backend.entity.*;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.erp.backend.service.CustomerTestData.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrderReservationExpiryServiceTest {

    @Mock private SalesOrderRepository orderRepository;
    @Mock private SalesOrderApprovalRepository approvalRepository;
    @Mock private InventoryService inventoryService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks private OrderReservationExpiryService service;

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 9, 5);
    private SalesOrder order;

    @BeforeEach
    void setUp() {
        service.clock = Clock.fixed(NOW.atZone(VN).toInstant(), VN);
        service.reservationDays = 7;
        order = SalesOrder.builder().id(100L).code("DH261001-AAAA").customer(customer(6, salesRep(7)))
                .status(SalesOrder.STATUS_APPROVED).reservationStatus(SalesOrder.RESERVATION_RESERVED)
                .reservedAt(LocalDateTime.of(2026, 10, 1, 8, 0)).reservationExpiresAt(LocalDateTime.of(2026, 10, 8, 8, 0))
                .build();
        lenient().when(orderRepository.findByIdForUpdate(100L)).thenReturn(Optional.of(order));
        lenient().when(orderRepository.saveAndFlush(any(SalesOrder.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S5-06: Đơn đã duyệt quá hạn giữ chỗ -> nhả tồn, chuyển Huỷ, ghi lịch sử và nhật ký")
    void expired_releasedAndCancelled() {
        assertThat(service.expireOne(100L)).isTrue();

        verify(inventoryService).releaseReservedStock(order);
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_CANCELLED);
        assertThat(order.getCancelReason()).contains("quá hạn giữ chỗ 7 ngày");
        assertThat(order.getCancelledAt()).isEqualTo(NOW);
        ArgumentCaptor<SalesOrderApproval> history = ArgumentCaptor.forClass(SalesOrderApproval.class);
        verify(approvalRepository).save(history.capture());
        assertThat(history.getValue().getAction()).isEqualTo(SalesOrderApproval.ACTION_CANCEL);
        assertThat(history.getValue().getFromStatus()).isEqualTo(SalesOrder.STATUS_APPROVED);
        verify(auditLogService).record(eq(AuditModule.INVENTORY), eq("AUTO_RELEASE_RESERVATION"), eq("SALES_ORDER"),
                eq(100L), eq("DH261001-AAAA"), eq(SalesOrder.STATUS_APPROVED), eq(SalesOrder.STATUS_CANCELLED), anyString(), isNull());
    }

    @Test
    @DisplayName("S5-06: Chưa đến hạn -> không làm gì")
    void notYetExpired_skip() {
        order.setReservationExpiresAt(NOW.plusHours(1));

        assertThat(service.expireOne(100L)).isFalse();
        verifyNoInteractions(inventoryService, approvalRepository, auditLogService);
    }

    @Test
    @DisplayName("S5-06: Đơn vừa chuyển sang soạn hàng (kho đang lấy hàng) -> không tự huỷ")
    void picking_skip() {
        order.setStatus(SalesOrder.STATUS_PICKING);

        assertThat(service.expireOne(100L)).isFalse();
        verifyNoInteractions(inventoryService);
        assertThat(order.getStatus()).isEqualTo(SalesOrder.STATUS_PICKING);
    }

    @Test
    @DisplayName("S5-06: Đơn đã nhả giữ chỗ trước đó -> không nhả lần nữa")
    void alreadyReleased_skip() {
        order.setReservationStatus(SalesOrder.RESERVATION_RELEASED);

        assertThat(service.expireOne(100L)).isFalse();
        verifyNoInteractions(inventoryService);
    }

    @Test
    @DisplayName("S5-06: Quét đơn Chờ duyệt / Đã duyệt còn giữ chỗ và đã quá hạn tính đến hiện tại")
    void findExpired_queryArguments() {
        when(orderRepository.findExpiredReservationOrderIds(any(), any(), any(), any(Pageable.class))).thenReturn(List.of(100L));

        assertThat(service.findExpiredOrderIds()).containsExactly(100L);
        verify(orderRepository).findExpiredReservationOrderIds(
                eq(List.of(SalesOrder.STATUS_PENDING_APPROVAL, SalesOrder.STATUS_APPROVED)),
                eq(SalesOrder.RESERVATION_RESERVED), eq(NOW), any(Pageable.class));
    }

    @Test
    @DisplayName("S5-06: Một đơn lỗi khi nhả không chặn các đơn còn lại trong lần quét")
    void job_oneFailureDoesNotStopOthers() {
        OrderReservationExpiryService mockService = mock(OrderReservationExpiryService.class);
        when(mockService.findExpiredOrderIds()).thenReturn(List.of(1L, 2L, 3L));
        when(mockService.expireOne(1L)).thenReturn(true);
        when(mockService.expireOne(2L)).thenThrow(new IllegalStateException("khoá dòng tồn bị tranh chấp"));
        when(mockService.expireOne(3L)).thenReturn(true);

        assertThat(new OrderReservationExpiryJob(mockService).runOnce()).isEqualTo(2);
        verify(mockService).expireOne(3L);
    }
}
