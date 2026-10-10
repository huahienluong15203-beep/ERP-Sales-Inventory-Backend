package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.order.OrderLineDeliveryRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderApproval;
import com.erp.backend.entity.SalesOrderLine;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * S4-06 / SCRUM-158 & S5-01: Quản lý vòng đời và chuyển trạng thái đơn hàng:
 * - Vòng đời chuẩn: Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng (PICKING) -> Đã xuất (DISPATCHED) -> Đã giao (DELIVERED) -> Đóng (CLOSED).
 * - Nhánh Huỷ (CANCELLED): Bắt buộc nhập lý do (AC2). Tự động nhả tồn đang giữ chỗ (AC2).
 * - Đơn đã xuất kho (DISPATCHED trở đi) thì nghiêm cấm huỷ, phải đi đường trả hàng (AC3).
 * - S5-01: Ghi nhận số lượng thực giao (DELIVERED) và phát hiện giao thiếu.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_COMMENT = 500;

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final CustomerRepository customerRepository;
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
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);

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

    public OrderResponse transitionStatus(Long id, String targetStatus, String note, UserDetailsImpl actor) {
        return transitionStatus(id, targetStatus, note, null, actor);
    }

    /**
     * S4-06 & S5-01: Chuyển trạng thái đơn hàng theo các nấc của vòng đời:
     * APPROVED -> PICKING -> DISPATCHED -> DELIVERED -> CLOSED.
     * S5-01: hỗ trợ lineDeliveries khi chuyển sang DELIVERED để ghi nhận số lượng thực giao và phát hiện giao thiếu.
     */
    @Transactional
    public OrderResponse transitionStatus(Long id, String targetStatus, String note,
                                          List<OrderLineDeliveryRequest> lineDeliveries, UserDetailsImpl actor) {
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
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);

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

        // S5-01: Cập nhật số lượng thực giao khi giao hàng (DELIVERED)
        boolean hasShortageDelivery = false;
        if (SalesOrder.STATUS_DELIVERED.equals(cleanTarget)) {
            if (lineDeliveries != null && !lineDeliveries.isEmpty()) {
                applyLineDeliveries(order, lineDeliveries);
            } else {
                // Nếu không gửi chi tiết, các dòng chưa có số lượng thực giao mặc định coi như giao đủ
                for (SalesOrderLine line : order.getLines()) {
                    if (line.getDeliveredQuantity() == null) {
                        line.setDeliveredQuantity(line.getQuantity());
                    }
                }
            }
            hasShortageDelivery = order.getLines().stream().anyMatch(l ->
                    l.getDeliveredQuantity() != null && l.getQuantity() != null
                            && l.getDeliveredQuantity().compareTo(l.getQuantity()) < 0);
        }

        order.setStatus(cleanTarget);
        if (StringUtils.hasText(cleanNote)) {
            order.setLastApprovalComment(cleanNote);
        }

        String comment;
        if (StringUtils.hasText(cleanNote)) {
            comment = cleanNote;
        } else if (SalesOrder.STATUS_DELIVERED.equals(cleanTarget) && hasShortageDelivery) {
            comment = "Đại lý đã nhận hàng (có ghi nhận giao thiếu một số dòng hàng)";
        } else {
            comment = defaultActionNote(cleanTarget);
        }

        saveHistory(order, cleanTarget, currentStatus, cleanTarget, comment, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, "CHANGE_STATUS_" + cleanTarget, currentStatus, cleanTarget, comment, actor);

        log.info("S4-06: Đơn hàng {} chuyển trạng thái từ {} sang {}, thực hiện bởi {}",
                saved.getCode(), currentStatus, cleanTarget, actor != null ? actor.getUsername() : "anonymous");

        return orderDraftService.toResponse(saved);
    }

    /**
     * S5-01 / SCRUM-160: API cập nhật số lượng thực giao cho từng dòng hàng trong đơn hàng.
     */
    @Transactional
    public OrderResponse updateDeliveredQuantities(Long id, List<OrderLineDeliveryRequest> lineDeliveries, UserDetailsImpl actor) {
        if (lineDeliveries == null || lineDeliveries.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERY_LIST_EMPTY",
                    "Danh sách dòng hàng thực giao không được để trống", "lineDeliveries");
        }

        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);

        applyLineDeliveries(order, lineDeliveries);

        SalesOrder saved = orderRepository.saveAndFlush(order);
        log.info("S5-01: Đã cập nhật số lượng thực giao cho đơn hàng {}, thực hiện bởi {}",
                saved.getCode(), actor != null ? actor.getUsername() : "anonymous");

        return orderDraftService.toResponse(saved);
    }

    private void applyLineDeliveries(SalesOrder order, List<OrderLineDeliveryRequest> lineDeliveries) {
        for (OrderLineDeliveryRequest req : lineDeliveries) {
            if (req.getLineId() == null) {
                continue;
            }
            SalesOrderLine targetLine = order.getLines().stream()
                    .filter(l -> l.getId().equals(req.getLineId()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_LINE_NOT_FOUND",
                            "Không tìm thấy dòng hàng với ID " + req.getLineId(), "lineDeliveries"));

            if (req.getDeliveredQuantity() == null || req.getDeliveredQuantity().compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DELIVERED_QUANTITY",
                        "Số lượng thực giao không được để trống hoặc âm", "deliveredQuantity");
            }

            targetLine.setDeliveredQuantity(req.getDeliveredQuantity());
            if (StringUtils.hasText(req.getShortageReason())) {
                targetLine.setShortageReason(req.getShortageReason().trim());
            } else if (req.getDeliveredQuantity().compareTo(targetLine.getQuantity()) >= 0) {
                targetLine.setShortageReason(null);
            }
        }
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
