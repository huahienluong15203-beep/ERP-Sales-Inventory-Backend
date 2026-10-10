package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderApproval;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * S4-06 / SCRUM-158: Quản lý vòng đời và chuyển trạng thái đơn hàng:
 * - Vòng đời chuẩn: Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng (PICKING) -> Đã xuất (DISPATCHED) -> Đã giao (DELIVERED) -> Đóng (CLOSED).
 * - Nhánh Huỷ (CANCELLED): Bắt buộc nhập lý do (AC2). Tự động nhả tồn đang giữ chỗ (AC2).
 * - Đơn đã xuất kho (DISPATCHED trở đi) thì nghiêm cấm huỷ, phải đi đường trả hàng (AC3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_COMMENT = 500;

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final InventoryService inventoryService;
    private final AuditLogService auditLogService;
    private final OrderDraftService orderDraftService;

    Clock clock = Clock.system(VN_ZONE);

    /**
     * S4-06 AC2 & AC3: Huỷ đơn hàng.
     * Bắt buộc có lý do (AC2) và tự động nhả tồn kho đang giữ chỗ (AC2).
     * Nếu đơn đã xuất kho trở đi (DISPATCHED, DELIVERED, CLOSED) -> Chặn huỷ (AC3).
     */
    @Transactional
    public OrderResponse cancel(Long id, String reason, UserDetailsImpl actor) {
        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer());

        String cleanReason = reason != null ? reason.trim() : "";
        if (!StringUtils.hasText(cleanReason)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Bắt buộc nhập lý do hủy đơn hàng theo quy chuẩn S4-06 AC2!", "reason");
        }
        if (cleanReason.length() > MAX_COMMENT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG",
                    "Lý do hủy tối đa " + MAX_COMMENT + " ký tự", "reason");
        }

        String currentStatus = order.getStatus() != null ? order.getStatus().toUpperCase() : "";

        // S4-06 AC3: Đơn đã xuất kho thì không huỷ được, phải đi đường trả hàng
        if (SalesOrder.STATUS_DISPATCHED.equalsIgnoreCase(currentStatus)
                || SalesOrder.STATUS_DELIVERED.equalsIgnoreCase(currentStatus)
                || SalesOrder.STATUS_CLOSED.equalsIgnoreCase(currentStatus)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_ALREADY_DISPATCHED",
                    "Đơn hàng đã xuất kho hoặc đã hoàn tất. Theo quy chuẩn S4-06 AC3, không thể hủy đơn mà phải làm thủ tục trả hàng (EP-08).",
                    "status");
        }

        if (SalesOrder.STATUS_CANCELLED.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("ORDER_ALREADY_CANCELLED",
                    "Đơn hàng đã ở trạng thái hủy trước đó", "status");
        }

        if (SalesOrder.STATUS_REJECTED.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("ORDER_ALREADY_REJECTED",
                    "Đơn hàng đã bị từ chối duyệt, không thể hủy", "status");
        }

        // S4-06 AC2: Tự động nhả tồn đang giữ chỗ nếu đơn ở trạng thái đã giữ chỗ tồn kho
        if (SalesOrder.STATUS_PENDING_APPROVAL.equalsIgnoreCase(currentStatus)
                || SalesOrder.STATUS_APPROVED.equalsIgnoreCase(currentStatus)
                || SalesOrder.STATUS_PICKING.equalsIgnoreCase(currentStatus)) {
            inventoryService.releaseReservedStock(order);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        order.setStatus(SalesOrder.STATUS_CANCELLED);
        order.setLastApprovalComment(cleanReason);
        order.setCancelReason(cleanReason);
        order.setCancelledAt(now);
        order.setCancelledById(actor != null ? actor.getId() : null);
        order.setCancelledByUsername(actor != null ? actor.getUsername() : null);

        saveHistory(order, SalesOrderApproval.ACTION_CANCEL, currentStatus, SalesOrder.STATUS_CANCELLED, cleanReason, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, "CANCEL_ORDER", currentStatus, SalesOrder.STATUS_CANCELLED, cleanReason, actor);

        log.info("S4-06: Đã hủy đơn hàng {} từ trạng thái {}, lý do: '{}', thực hiện bởi: {}",
                saved.getCode(), currentStatus, cleanReason, actor != null ? actor.getUsername() : "anonymous");

        return orderDraftService.toResponse(saved);
    }

    /**
     * S4-06 / SCRUM-158: Chuyển trạng thái đơn hàng theo các nấc của vòng đời:
     * APPROVED -> PICKING -> DISPATCHED -> DELIVERED -> CLOSED.
     */
    @Transactional
    public OrderResponse transitionStatus(Long id, String targetStatus, String note, UserDetailsImpl actor) {
        if (!StringUtils.hasText(targetStatus)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STATUS_REQUIRED",
                    "Trạng thái mới không được để trống", "status");
        }
        String cleanTarget = targetStatus.trim().toUpperCase();

        // Nếu chuyển sang CANCELLED thì đi theo quy trình huỷ đơn (bắt buộc lý do & nhả tồn)
        if (SalesOrder.STATUS_CANCELLED.equals(cleanTarget)) {
            return cancel(id, note, actor);
        }

        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer());

        String currentStatus = order.getStatus() != null ? order.getStatus().toUpperCase() : "";

        // Kiểm tra chuyển bước hợp lệ
        validateStatusTransition(currentStatus, cleanTarget);

        String cleanNote = note != null ? note.trim() : "";
        if (cleanNote.length() > MAX_COMMENT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG",
                    "Ghi chú tối đa " + MAX_COMMENT + " ký tự", "note");
        }

        // Khi xuất kho (DISPATCHED): trừ tồn thực tế và giải phóng giữ chỗ
        if (SalesOrder.STATUS_DISPATCHED.equals(cleanTarget)) {
            inventoryService.dispatchReservedStock(order);
        }

        order.setStatus(cleanTarget);
        if (StringUtils.hasText(cleanNote)) {
            order.setLastApprovalComment(cleanNote);
        }

        String comment = StringUtils.hasText(cleanNote) ? cleanNote : defaultActionNote(cleanTarget);
        saveHistory(order, cleanTarget, currentStatus, cleanTarget, comment, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, "CHANGE_STATUS_" + cleanTarget, currentStatus, cleanTarget, comment, actor);

        log.info("S4-06: Đơn hàng {} chuyển trạng thái từ {} sang {}, thực hiện bởi {}",
                saved.getCode(), currentStatus, cleanTarget, actor != null ? actor.getUsername() : "anonymous");

        return orderDraftService.toResponse(saved);
    }

    private void validateStatusTransition(String currentStatus, String targetStatus) {
        boolean valid = switch (currentStatus) {
            case SalesOrder.STATUS_APPROVED ->
                    SalesOrder.STATUS_PICKING.equals(targetStatus);
            case SalesOrder.STATUS_PICKING ->
                    SalesOrder.STATUS_DISPATCHED.equals(targetStatus);
            case SalesOrder.STATUS_DISPATCHED ->
                    SalesOrder.STATUS_DELIVERED.equals(targetStatus);
            case SalesOrder.STATUS_DELIVERED ->
                    SalesOrder.STATUS_CLOSED.equals(targetStatus);
            default -> false;
        };

        if (!valid) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    String.format("Không thể chuyển trạng thái đơn hàng từ '%s' sang '%s'", currentStatus, targetStatus),
                    "status");
        }
    }

    private static String defaultActionNote(String status) {
        return switch (status) {
            case SalesOrder.STATUS_PICKING -> "Kho bắt đầu soạn hàng theo nguyên tắc FEFO";
            case SalesOrder.STATUS_DISPATCHED -> "Hàng đã xuất kho và bắt đầu giao cho đại lý";
            case SalesOrder.STATUS_DELIVERED -> "Đại lý đã nhận đủ hàng thành công";
            case SalesOrder.STATUS_CLOSED -> "Hoàn tất và đóng đơn hàng";
            default -> "Chuyển trạng thái sang " + status;
        };
    }

    private void saveHistory(SalesOrder order, String action, String fromStatus, String toStatus, String comment,
                             UserDetailsImpl actor) {
        String c = comment != null && comment.length() > 1000 ? comment.substring(0, 1000) : comment;
        approvalRepository.save(SalesOrderApproval.builder()
                .order(order)
                .action(action)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .comment(c)
                .actorId(actor != null ? actor.getId() : null)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorFullName(actor != null ? actor.getFullName() : null)
                .build());
    }

    private void audit(SalesOrder order, String action, String fromStatus, String toStatus, String comment,
                       UserDetailsImpl actor) {
        auditLogService.record(AuditModule.INVOICE, action, "SALES_ORDER", order.getId(), order.getCode(),
                fromStatus, toStatus, comment, actor);
        markAuditLogged();
    }

    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request thì bỏ qua
        }
    }
}
