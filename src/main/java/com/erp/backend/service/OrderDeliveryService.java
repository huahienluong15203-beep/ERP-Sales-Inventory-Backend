package com.erp.backend.service;

import com.erp.backend.dto.order.OrderDeliveryConfirmRequest;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderLine;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.SalesOrderApprovalRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * S6-08: Xác nhận giao hàng.
 * - Tải ảnh biên bản giao hàng có chữ ký (đơn Đã xuất), xem lại qua API có kiểm quyền.
 * - Xác nhận giao thành công: người nhận, thời điểm giao, số thực giao; bắt buộc đã có ảnh biên bản.
 *   Phần giao thiếu chở về được nhập lại kho (thẻ kho). Sau khi xác nhận thì khoá số thực giao -> dùng lập hoá đơn (S7-01).
 * - Giao không thành công: bắt buộc lý do, toàn bộ hàng nhập lại kho, đơn sang DELIVERY_FAILED (không tính công nợ).
 * - S6-07: đơn đang trên chuyến giao còn chạy thì hàng giao không được vẫn thuộc chuyến, chỉ nhập lại kho khi kết thúc chuyến.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderDeliveryService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Ai được ghi nhận giao hàng: kho / QL kho / QL kinh doanh / Admin; NV kinh doanh chỉ đơn đại lý mình phụ trách. */
    static final Set<String> DELIVERY_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_WAREHOUSE",
            "ROLE_WH_MANAGER", "ROLE_SALES_REP");
    static final int MAX_REASON = 500;
    /** Cho lệch đồng hồ máy người dùng vài phút. */
    static final long FUTURE_TOLERANCE_MINUTES = 5;
    static final String ACTION_DELIVERY_FAILED = "DELIVERY_FAILED";

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final CustomerRepository customerRepository;
    private final UserRepository userRepository;
    private final InventoryService inventoryService;
    private final AuditLogService auditLogService;
    private final OrderDraftService orderDraftService;
    private final DeliveryProofStorageService proofStorage;

    Clock clock = Clock.system(VN_ZONE);

    // ======================= ẢNH BIÊN BẢN =======================

    @Transactional
    public OrderResponse uploadProof(Long id, MultipartFile file, UserDetailsImpl actor) {
        SalesOrder order = lockForDelivery(id, actor);
        requireDispatched(order, "tải ảnh biên bản giao hàng");

        DeliveryProofStorageService.StoredFile stored = proofStorage.store(order.getId(), file);
        // Transaction lỗi thì xoá ảnh vừa ghi (không để tệp mồ côi); thành công thì xoá ảnh cũ bị thay
        onRollback(() -> proofStorage.deleteQuietly(stored.fileName()));
        String old = order.getDeliveryProofFile();
        order.setDeliveryProofFile(stored.fileName());
        order.setDeliveryProofContentType(stored.contentType());
        order.setDeliveryProofUploadedAt(LocalDateTime.now(clock));
        SalesOrder saved = orderRepository.saveAndFlush(order);
        OrderStatusService.audit(auditLogService, saved, "UPLOAD_DELIVERY_PROOF", old, stored.fileName(),
                "Tải ảnh biên bản giao hàng đơn " + saved.getCode(), actor);
        if (old != null && !old.equals(stored.fileName())) {
            AfterCommit.run(() -> proofStorage.deleteQuietly(old));
        }
        return orderDraftService.toResponse(saved);
    }

    /** Xem ảnh biên bản: ai xem được đơn thì xem được ảnh (đại lý chỉ đơn của mình, NV kinh doanh chỉ đại lý mình). */
    @Transactional(readOnly = true)
    public DeliveryProofStorageService.LoadedFile loadProof(Long id, UserDetailsImpl actor) {
        SalesOrder order = orderRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);
        if (order.getDeliveryProofFile() == null) {
            throw BusinessException.notFound("Đơn chưa có ảnh biên bản giao hàng");
        }
        return proofStorage.load(order.getDeliveryProofFile());
    }

    // ======================= GIAO THÀNH CÔNG =======================

    @Transactional
    public OrderResponse confirmDelivery(Long id, OrderDeliveryConfirmRequest request, UserDetailsImpl actor) {
        OrderDeliveryConfirmRequest req = request != null ? request : new OrderDeliveryConfirmRequest();
        String receiver = req.getReceiverName() != null ? req.getReceiverName().trim() : "";
        if (!StringUtils.hasText(receiver)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECEIVER_REQUIRED",
                    "Bắt buộc nhập tên người nhận hàng", "receiverName");
        }
        if (receiver.length() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECEIVER_TOO_LONG",
                    "Tên người nhận tối đa 100 ký tự", "receiverName");
        }
        String note = req.getNote() != null ? req.getNote().trim() : "";
        if (note.length() > MAX_REASON) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG",
                    "Ghi chú tối đa " + MAX_REASON + " ký tự", "note");
        }

        SalesOrder order = lockForDelivery(id, actor);
        requireDispatched(order, "xác nhận giao hàng");
        if (order.getDeliveryProofFile() == null) {
            throw BusinessException.conflict("DELIVERY_PROOF_REQUIRED",
                    "Vui lòng tải ảnh biên bản giao hàng có chữ ký trước khi xác nhận giao", "deliveryProof");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime deliveredAt = req.getDeliveredAt() != null ? req.getDeliveredAt() : now;
        if (deliveredAt.isAfter(now.plusMinutes(FUTURE_TOLERANCE_MINUTES))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERED_AT_IN_FUTURE",
                    "Thời điểm giao hàng không được ở tương lai", "deliveredAt");
        }
        if (order.getDispatchedAt() != null && deliveredAt.isBefore(order.getDispatchedAt())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERED_BEFORE_DISPATCH",
                    "Thời điểm giao hàng không được trước lúc xuất kho", "deliveredAt");
        }

        String before = OrderStatusService.deliverySummary(order);
        if (req.getLineDeliveries() != null && !req.getLineDeliveries().isEmpty()) {
            OrderStatusService.applyLineDeliveries(order, req.getLineDeliveries());
        }
        List<InventoryService.DispatchLine> returns = new ArrayList<>();
        boolean anyDelivered = false;
        int shortLines = 0;
        for (SalesOrderLine line : order.getLines()) {
            if (line.getDeliveredQuantity() == null) {
                line.setDeliveredQuantity(line.getQuantity());
            }
            if (line.getDeliveredQuantity().signum() > 0) {
                anyDelivered = true;
            }
            if (line.getDeliveredQuantity().compareTo(line.getQuantity()) < 0) {
                shortLines++;
                if (!StringUtils.hasText(line.getShortageReason())) {
                    if (!StringUtils.hasText(note)) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERY_SHORTAGE_REASON_REQUIRED",
                                "Dòng " + line.getProductSku() + " giao thiếu: bắt buộc nhập lý do", "shortageReason");
                    }
                    line.setShortageReason(OrderStatusService.cut(note, 255));
                }
                returns.add(new InventoryService.DispatchLine(line, undeliveredBase(line)));
            }
        }
        if (!anyDelivered && !order.getLines().isEmpty()) {
            throw BusinessException.conflict("NOTHING_DELIVERED",
                    "Đại lý không nhận dòng hàng nào: hãy ghi nhận Giao không thành công", "lineDeliveries");
        }

        // Hàng giao thiếu được chở về: nhập lại kho đã xuất (cùng transaction, ghi thẻ kho).
        // S6-07: đơn đang trên 1 chuyến giao còn chạy thì hàng vẫn thuộc trách nhiệm chuyến, nhập lại khi kết thúc chuyến
        if (!DeliveryTripService.onActiveTrip(order)) {
            inventoryService.returnDeliveredStock(order, returns,
                    "Hàng giao thiếu chở về kho (đơn " + order.getCode() + ")", actor);
            order.setStockReturnedAt(now);
        }

        order.setReceiverName(receiver);
        order.setDeliveredAt(deliveredAt);
        order.setDeliveryConfirmedAt(now);
        order.setDeliveryConfirmedByUsername(actor != null ? actor.getUsername() : null);
        order.setStatus(SalesOrder.STATUS_DELIVERED);
        if (StringUtils.hasText(note)) {
            order.setLastApprovalComment(note);
        }
        String comment = "Đại lý đã nhận hàng, người nhận: " + receiver
                + (shortLines > 0 ? " (giao thiếu " + shortLines + " dòng, " + (DeliveryTripService.onActiveTrip(order)
                ? "phần thiếu còn trên chuyến " + order.getDeliveryTrip().getCode() + ", nhập lại kho khi kết thúc chuyến)"
                : "phần thiếu đã nhập lại kho)") : "")
                + (StringUtils.hasText(note) ? ". " + note : "");
        OrderStatusService.saveHistory(approvalRepository, order, SalesOrder.STATUS_DELIVERED,
                SalesOrder.STATUS_DISPATCHED, SalesOrder.STATUS_DELIVERED, comment, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        OrderStatusService.audit(auditLogService, saved, "CONFIRM_DELIVERY", before,
                OrderStatusService.deliverySummary(saved), comment, actor);
        log.info("S6-08: Đã xác nhận giao đơn {} cho {}, giao thiếu {} dòng", saved.getCode(), receiver, shortLines);
        return orderDraftService.toResponse(saved);
    }

    // ======================= GIAO KHÔNG THÀNH CÔNG =======================

    @Transactional
    public OrderResponse markFailed(Long id, String reason, UserDetailsImpl actor) {
        String why = reason != null ? reason.trim() : "";
        if (!StringUtils.hasText(why)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Bắt buộc nhập lý do giao không thành công", "reason");
        }
        if (why.length() > MAX_REASON) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_TOO_LONG",
                    "Lý do tối đa " + MAX_REASON + " ký tự", "reason");
        }
        SalesOrder order = lockForDelivery(id, actor);
        requireDispatched(order, "ghi nhận giao không thành công");

        String before = OrderStatusService.deliverySummary(order);
        List<InventoryService.DispatchLine> returns = new ArrayList<>();
        for (SalesOrderLine line : order.getLines()) {
            returns.add(new InventoryService.DispatchLine(line, InventoryService.lineBaseQuantity(line)));
            line.setDeliveredQuantity(BigDecimal.ZERO);
            line.setShortageReason(OrderStatusService.cut(why, 255));
        }
        LocalDateTime now = LocalDateTime.now(clock);
        // Toàn bộ hàng đã xuất được chở về: nhập lại kho đã xuất.
        // S6-07: đơn đang trên chuyến giao còn chạy thì chờ kết thúc chuyến mới nhập lại
        if (!DeliveryTripService.onActiveTrip(order)) {
            inventoryService.returnDeliveredStock(order, returns,
                    "Giao không thành công, nhập lại kho (đơn " + order.getCode() + "): " + why, actor);
            order.setStockReturnedAt(now);
        }
        order.setStatus(SalesOrder.STATUS_DELIVERY_FAILED);
        order.setDeliveryFailedReason(why);
        order.setDeliveryFailedAt(now);
        order.setLastApprovalComment(why);
        OrderStatusService.saveHistory(approvalRepository, order, ACTION_DELIVERY_FAILED,
                SalesOrder.STATUS_DISPATCHED, SalesOrder.STATUS_DELIVERY_FAILED, why, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        OrderStatusService.audit(auditLogService, saved, "DELIVERY_FAILED", before,
                OrderStatusService.deliverySummary(saved), why, actor);
        log.info("S6-08: Đơn {} giao không thành công, đã nhập lại kho. Lý do: {}", saved.getCode(), why);
        return orderDraftService.toResponse(saved);
    }

    // ======================= TIỆN ÍCH =======================

    private SalesOrder lockForDelivery(Long id, UserDetailsImpl actor) {
        OrderStatusService.requireAnyRole(actor, DELIVERY_ROLES, "Bạn không có quyền ghi nhận giao hàng");
        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);
        OrderStatusService.assertAssignedWarehouse(actor, order, userRepository);
        return order;
    }

    private static void requireDispatched(SalesOrder order, String action) {
        if (!SalesOrder.STATUS_DISPATCHED.equalsIgnoreCase(order.getStatus())) {
            throw BusinessException.conflict("DELIVERY_NOT_ALLOWED",
                    "Chỉ " + action + " khi đơn đang ở trạng thái Đã xuất kho (hiện tại: " + order.getStatus() + ")",
                    "status");
        }
    }

    /** Số lượng ĐVT cơ sở chưa giao của dòng = (số xuất - số giao) x hệ số, không vượt số đã xuất. */
    static BigDecimal undeliveredBase(SalesOrderLine line) {
        BigDecimal lineBase = InventoryService.lineBaseQuantity(line);
        if (line.getDeliveredQuantity() == null || line.getDeliveredQuantity().signum() == 0) {
            return lineBase;
        }
        BigDecimal factor = line.getConversionFactor() != null && line.getConversionFactor().signum() > 0
                ? line.getConversionFactor() : BigDecimal.ONE;
        BigDecimal deliveredBase = line.getDeliveredQuantity().multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal rest = lineBase.subtract(deliveredBase);
        return rest.signum() < 0 ? BigDecimal.ZERO : rest;
    }

    private static void onRollback(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    task.run();
                }
            }
        });
    }
}
