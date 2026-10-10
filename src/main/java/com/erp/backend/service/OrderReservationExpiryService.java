package com.erp.backend.service;

import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderApproval;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * S5-06: Đơn quá hạn giữ chỗ thì tồn được nhả tự động.
 * Đơn Chờ duyệt / Đã duyệt (chưa soạn hàng) mà quá hạn giữ chỗ -> nhả tồn đang giữ và chuyển Huỷ,
 * ghi lịch sử đơn và nhật ký tồn kho. Mỗi đơn xử lý trong một transaction riêng (gọi từ OrderReservationExpiryJob),
 * lỗi giữa chừng thì đơn đó hoàn lại toàn bộ, không ảnh hưởng đơn khác.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderReservationExpiryService {

    static final List<String> EXPIRABLE_STATUSES = List.of(SalesOrder.STATUS_PENDING_APPROVAL, SalesOrder.STATUS_APPROVED);
    static final int BATCH_SIZE = 200;

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final InventoryService inventoryService;
    private final AuditLogService auditLogService;

    @Value("${erp.order.reservation-days:7}")
    int reservationDays = 7;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Transactional(readOnly = true)
    public List<Long> findExpiredOrderIds() {
        return orderRepository.findExpiredReservationOrderIds(EXPIRABLE_STATUSES, SalesOrder.RESERVATION_RESERVED,
                LocalDateTime.now(clock), PageRequest.of(0, BATCH_SIZE));
    }

    /**
     * Nhả giữ chỗ và huỷ một đơn quá hạn. Khoá dòng đơn rồi kiểm tra lại (đơn có thể vừa được soạn hàng / huỷ tay).
     *
     * @return true nếu đã nhả và huỷ, false nếu đơn không còn thuộc diện quá hạn
     */
    @Transactional
    public boolean expireOne(Long orderId) {
        SalesOrder order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        LocalDateTime now = LocalDateTime.now(clock);
        if (order == null
                || !EXPIRABLE_STATUSES.contains(order.getStatus())
                || !SalesOrder.RESERVATION_RESERVED.equals(order.getReservationStatus())
                || order.getReservationExpiresAt() == null
                || !order.getReservationExpiresAt().isBefore(now)) {
            return false;
        }
        String fromStatus = order.getStatus();
        String reason = "Tự huỷ do quá hạn giữ chỗ " + reservationDays + " ngày (giữ từ "
                + (order.getReservedAt() != null ? order.getReservedAt().toLocalDate() : "?") + "), hệ thống đã nhả tồn";

        inventoryService.releaseReservedStock(order);
        order.setStatus(SalesOrder.STATUS_CANCELLED);
        order.setCancelReason(reason);
        order.setCancelledAt(now);
        order.setCancelledById(null);
        order.setCancelledByUsername("system");

        approvalRepository.save(SalesOrderApproval.builder()
                .order(order)
                .action(SalesOrderApproval.ACTION_CANCEL)
                .fromStatus(fromStatus)
                .toStatus(SalesOrder.STATUS_CANCELLED)
                .comment(reason)
                .actorUsername("system")
                .actorFullName("Hệ thống")
                .build());
        SalesOrder saved = orderRepository.saveAndFlush(order);
        auditLogService.record(AuditModule.INVENTORY, "AUTO_RELEASE_RESERVATION", "SALES_ORDER", saved.getId(),
                saved.getCode(), fromStatus, SalesOrder.STATUS_CANCELLED, reason, null);
        log.info("S5-06: Đơn {} quá hạn giữ chỗ -> đã nhả tồn và huỷ", saved.getCode());
        return true;
    }
}
