package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.order.OrderApprovalHistoryResponse;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.dto.order.PendingOrderResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.security.UserDetailsImpl;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * S4-05: Chốt đơn và duyệt đơn vượt hạn mức / dưới giá sàn.
 * - Chốt (NV kinh doanh): tính lại giá, kiểm tra đại lý; không vi phạm -> Đã duyệt ngay, vi phạm -> Chờ duyệt kèm lý do + mức vi phạm.
 * - QL kinh doanh: Duyệt / Từ chối / Trả lại sửa; Từ chối và Trả lại sửa bắt buộc nhập ý kiến.
 * - Mỗi bước ghi vào lịch sử duyệt (chỉ thêm, không sửa / xoá) và nhật ký hệ thống.
 * - S5-06: đơn sang Đã duyệt thì giữ chỗ tồn (làm ở S5-06, gọi tại onApproved).
 */
@Service
@RequiredArgsConstructor
public class OrderApprovalService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_COMMENT = 500;
    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final CustomerRepository customerRepository;
    private final OrderDraftService orderDraftService;
    private final CustomerCreditService creditService;
    private final AuditLogService auditLogService;
    private final InventoryService inventoryService;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= CHỐT ĐƠN =======================

    @Transactional
    public OrderResponse submit(Long id, UserDetailsImpl actor) {
        SalesOrder order = lockOrder(id, actor);
        if (!SalesOrder.STATUS_DRAFT.equals(order.getStatus())) {
            throw BusinessException.conflict("ORDER_NOT_DRAFT", "Chỉ chốt được đơn đang ở trạng thái nháp", null);
        }
        if (order.getLines().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_EMPTY", "Đơn chưa có dòng hàng nào", "lines");
        }
        // Khoá dòng đại lý: 2 đơn của cùng đại lý chốt cùng lúc không cùng lọt qua kiểm tra hạn mức
        customerRepository.findByIdForUpdate(order.getCustomer().getId());
        orderDraftService.recalculateForSubmit(order, actor);

        // S4-03 AC4: Kiểm tra và giữ chỗ tồn kho với khoá bi quan (PESSIMISTIC_WRITE)
        inventoryService.checkAndReserveStock(order);

        CreditStatusResponse credit = creditService.evaluate(order.getCustomer(), order.getTotalAmount());
        applyViolations(order, credit);
        boolean needsApproval = !OrderApprovalReasons.of(order).isEmpty();

        LocalDateTime now = LocalDateTime.now(clock);
        order.setSubmittedAt(now);
        order.setSubmittedByUsername(actor != null ? actor.getUsername() : null);
        order.setLastApprovalComment(null);
        String action;
        String comment;
        if (needsApproval) {
            order.setStatus(SalesOrder.STATUS_PENDING_APPROVAL);
            action = SalesOrderApproval.ACTION_SUBMIT;
            comment = OrderApprovalReasons.of(order).stream()
                    .map(r -> r.label() + ": " + r.detail()).collect(Collectors.joining("; "));
        } else {
            markApproved(order, null, now);
            action = SalesOrderApproval.ACTION_AUTO_APPROVE;
            comment = "Không vượt hạn mức công nợ, không bán dưới giá sàn";
        }
        saveHistory(order, action, SalesOrder.STATUS_DRAFT, comment, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, needsApproval ? "SUBMIT_ORDER_FOR_APPROVAL" : "AUTO_APPROVE_ORDER",
                SalesOrder.STATUS_DRAFT, comment, actor);
        if (!needsApproval) {
            onApproved(saved);
        }
        return orderDraftService.toResponse(saved);
    }

    /** Ghi mức vi phạm lúc chốt: vượt hạn mức công nợ (S4-02) và bán dưới giá sàn (S4-01). */
    void applyViolations(SalesOrder order, CreditStatusResponse credit) {
        if (credit != null && credit.exceedsLimit()) {
            order.setCreditExceededAmount(credit.exceededAmount());
            order.setCreditExceededPercent(credit.creditLimit() != null && credit.creditLimit().signum() > 0
                    ? credit.exceededAmount().multiply(HUNDRED).divide(credit.creditLimit(), 2, RoundingMode.HALF_UP)
                    : null);
        } else {
            order.setCreditExceededAmount(null);
            order.setCreditExceededPercent(null);
        }

        OrderDraftService.updateBelowFloorViolations(order);
    }

    // ======================= DUYỆT =======================

    @Transactional(readOnly = true)
    public PageResponse<PendingOrderResponse> pending(String keyword, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Specification<SalesOrder> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("status"), SalesOrder.STATUS_PENDING_APPROVAL));
            if (StringUtils.hasText(keyword)) {
                Join<SalesOrder, Customer> customer = root.join("customer");
                String like = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(customer.get("code")), like),
                        cb.like(cb.lower(customer.get("name")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        // Đơn chờ lâu nhất lên đầu
        return PageResponse.of(orderRepository.findAll(spec, PageRequest.of(safePage, safeSize,
                        Sort.by(Sort.Direction.ASC, "submittedAt").and(Sort.by("id").ascending())))
                .map(o -> new PendingOrderResponse(o.getId(), o.getCode(), o.getCustomer().getId(),
                        o.getCustomer().getCode(), o.getCustomer().getName(), o.getLines().size(), o.getTotalAmount(),
                        o.getSubmittedByUsername(), o.getSubmittedAt(), OrderApprovalReasons.of(o))));
    }

    @Transactional
    public OrderResponse approve(Long id, String comment, UserDetailsImpl actor) {
        SalesOrder order = lockPending(id, actor);
        String note = trimComment(comment, false);
        markApproved(order, actor, LocalDateTime.now(clock));
        order.setLastApprovalComment(note);
        return finishDecision(order, SalesOrderApproval.ACTION_APPROVE, "APPROVE_ORDER", note, actor);
    }

    @Transactional
    public OrderResponse reject(Long id, String comment, UserDetailsImpl actor) {
        String note = trimComment(comment, true);
        SalesOrder order = lockPending(id, actor);
        // S4-03: Giải phóng hàng giữ chỗ khi đơn hàng bị từ chối
        inventoryService.releaseReservedStock(order);
        order.setStatus(SalesOrder.STATUS_REJECTED);
        order.setLastApprovalComment(note);
        return finishDecision(order, SalesOrderApproval.ACTION_REJECT, "REJECT_ORDER", note, actor);
    }

    /** Trả lại sửa: đơn về Nháp, NV kinh doanh sửa rồi chốt lại. */
    @Transactional
    public OrderResponse returnForEdit(Long id, String comment, UserDetailsImpl actor) {
        String note = trimComment(comment, true);
        SalesOrder order = lockPending(id, actor);
        // S4-03: Giải phóng hàng giữ chỗ khi đơn được trả lại để sửa
        inventoryService.releaseReservedStock(order);
        order.setStatus(SalesOrder.STATUS_DRAFT);
        order.setLastApprovalComment(note);
        return finishDecision(order, SalesOrderApproval.ACTION_RETURN, "RETURN_ORDER", note, actor);
    }

    @Transactional(readOnly = true)
    public List<OrderApprovalHistoryResponse> history(Long id, UserDetailsImpl actor) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer());
        return approvalRepository.findByOrder_IdOrderByCreatedAtAscIdAsc(id).stream()
                .map(a -> new OrderApprovalHistoryResponse(a.getId(), a.getAction(), actionLabel(a.getAction()),
                        a.getFromStatus(), a.getToStatus(), a.getComment(), a.getActorUsername(), a.getActorFullName(),
                        a.getCreatedAt()))
                .toList();
    }

    // ======================= HÀM PHỤ =======================

    private OrderResponse finishDecision(SalesOrder order, String action, String auditAction, String note,
                                         UserDetailsImpl actor) {
        saveHistory(order, action, SalesOrder.STATUS_PENDING_APPROVAL, note, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, auditAction, SalesOrder.STATUS_PENDING_APPROVAL, note, actor);
        if (SalesOrder.STATUS_APPROVED.equals(saved.getStatus())) {
            onApproved(saved);
        }
        return orderDraftService.toResponse(saved);
    }

    /** S5-06: giữ chỗ tồn cho đơn vừa được duyệt (cùng transaction với việc duyệt). */
    void onApproved(SalesOrder order) {
        // Chưa có sổ tồn kho (S5-03, S5-04 của Lê Hồng Phong); S5-06 sẽ giữ chỗ tồn tại đây.
    }

    private void markApproved(SalesOrder order, UserDetailsImpl approver, LocalDateTime now) {
        // S5-06: đơn được duyệt -> tồn tiếp tục được giữ chỗ, gia hạn để kho kịp soạn hàng
        inventoryService.renewReservation(order);
        order.setStatus(SalesOrder.STATUS_APPROVED);
        order.setApprovedAt(now);
        order.setApprovedById(approver != null ? approver.getId() : null);
        order.setApprovedByUsername(approver != null ? approver.getUsername() : null);
    }

    private SalesOrder lockOrder(Long id, UserDetailsImpl actor) {
        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer());
        return order;
    }

    private SalesOrder lockPending(Long id, UserDetailsImpl actor) {
        SalesOrder order = lockOrder(id, actor);
        if (!SalesOrder.STATUS_PENDING_APPROVAL.equals(order.getStatus())) {
            throw BusinessException.conflict("ORDER_NOT_PENDING",
                    "Đơn " + order.getCode() + " không ở trạng thái chờ duyệt (có thể đã được người khác xử lý)", null);
        }
        return order;
    }

    private static String trimComment(String comment, boolean required) {
        String c = comment != null ? comment.trim() : "";
        if (required && c.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "COMMENT_REQUIRED",
                    "Vui lòng nhập ý kiến khi từ chối hoặc trả lại sửa", "comment");
        }
        if (c.length() > MAX_COMMENT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "COMMENT_TOO_LONG",
                    "Ý kiến tối đa " + MAX_COMMENT + " ký tự", "comment");
        }
        return c.isEmpty() ? null : c;
    }

    private void saveHistory(SalesOrder order, String action, String fromStatus, String comment, UserDetailsImpl actor) {
        String c = comment != null && comment.length() > 1000 ? comment.substring(0, 1000) : comment;
        approvalRepository.save(SalesOrderApproval.builder()
                .order(order)
                .action(action)
                .fromStatus(fromStatus)
                .toStatus(order.getStatus())
                .comment(c)
                .actorId(actor != null ? actor.getId() : null)
                .actorUsername(actor != null ? actor.getUsername() : null)
                .actorFullName(actor != null ? actor.getFullName() : null)
                .build());
    }

    private void audit(SalesOrder order, String action, String fromStatus, String comment, UserDetailsImpl actor) {
        auditLogService.record(AuditModule.INVOICE, action, "SALES_ORDER", order.getId(), order.getCode(),
                fromStatus, order.getStatus(), comment, actor);
        markAuditLogged();
    }

    static String actionLabel(String action) {
        return switch (action) {
            case SalesOrderApproval.ACTION_SUBMIT -> "Gửi duyệt";
            case SalesOrderApproval.ACTION_AUTO_APPROVE -> "Tự duyệt khi chốt";
            case SalesOrderApproval.ACTION_APPROVE -> "Duyệt";
            case SalesOrderApproval.ACTION_REJECT -> "Từ chối";
            case SalesOrderApproval.ACTION_RETURN -> "Trả lại sửa";
            case SalesOrderApproval.ACTION_CANCEL -> "Hủy đơn";
            case "PICKING" -> "Soạn hàng";
            case "DISPATCHED" -> "Xuất kho";
            case "DELIVERED" -> "Đã giao hàng";
            case "CLOSED" -> "Đóng đơn";
            default -> action;
        };
    }

    /** Đã ghi nhật ký chi tiết -> AuditLogInterceptor không ghi thêm bản chung chung. */
    private static void markAuditLogged() {
        try {
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null && attributes.getRequest() != null) {
                attributes.getRequest().setAttribute(AuditLogInterceptor.AUDIT_LOGGED_ATTR, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // ngoài HTTP request (test) thì bỏ qua
        }
    }
}
