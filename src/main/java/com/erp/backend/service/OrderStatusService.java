package com.erp.backend.service;

import com.erp.backend.config.AuditLogInterceptor;
import com.erp.backend.dto.order.OrderDispatchRequest;
import com.erp.backend.dto.order.OrderDispatchResponse;
import com.erp.backend.dto.order.OrderLineDeliveryRequest;
import com.erp.backend.dto.order.OrderLineDispatchRequest;
import com.erp.backend.dto.inventory.lot.FefoLotSuggestionResponse;
import com.erp.backend.dto.order.OrderResponse;
import com.erp.backend.entity.AuditModule;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductLot;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesOrderApproval;
import com.erp.backend.entity.SalesOrderLine;
import com.erp.backend.entity.StockMovement;
import com.erp.backend.entity.Warehouse;
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
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * S4-06 / SCRUM-158 & S5-01: Quản lý vòng đời và chuyển trạng thái đơn hàng:
 * - Vòng đời chuẩn: Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng (PICKING) -> Đã xuất (DISPATCHED) -> Đã giao (DELIVERED) -> Đóng (CLOSED).
 * - Nhánh Huỷ (CANCELLED): Bắt buộc nhập lý do (AC2). Tự động nhả tồn đang giữ chỗ (AC2).
 * - Đơn đã xuất kho (DISPATCHED trở đi) thì nghiêm cấm huỷ, phải đi đường trả hàng (AC3).
 * - S5-01: Ghi nhận số lượng thực giao (DELIVERED) và phát hiện giao thiếu.
 * - S6-04: Gợi ý lô xuất FEFO, xác thực lý do chọn lô khác và trừ đúng tồn của lô đã chọn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderStatusService {

    static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    static final int MAX_COMMENT = 500;
    /** Huỷ đơn: cùng quyền với POST /api/orders/{id}/cancel (kho không được huỷ đơn). */
    static final Set<String> CANCEL_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_SALES_REP");
    /** Soạn hàng / xuất kho (trừ tồn thực tế): việc của kho; QL kinh doanh và Admin được làm thay khi cần. NV kinh doanh không. */
    static final Set<String> WAREHOUSE_STEP_ROLES = Set.of("ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_WAREHOUSE", "ROLE_WH_MANAGER");

    private final SalesOrderRepository orderRepository;
    private final SalesOrderApprovalRepository approvalRepository;
    private final CustomerRepository customerRepository;
    private final InventoryService inventoryService;
    private final ProductLotService productLotService;
    private final AuditLogService auditLogService;
    private final OrderDraftService orderDraftService;
    private final com.erp.backend.repository.UserRepository userRepository;

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
                    "Bắt buộc nhập lý do hủy đơn hàng!", "reason");
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
                    "Đơn hàng đã xuất kho hoặc đã hoàn tất. Không thể hủy đơn mà phải làm thủ tục trả hàng.",
                    "status");
        }

        if (SalesOrder.STATUS_DELIVERY_FAILED.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("ORDER_DELIVERY_FAILED",
                    "Đơn đã ghi nhận giao không thành công (hàng đã nhập lại kho), không cần huỷ", "status");
        }

        if (SalesOrder.STATUS_CANCELLED.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("ORDER_ALREADY_CANCELLED",
                    "Đơn hàng đã ở trạng thái hủy trước đó", "status");
        }

        if (SalesOrder.STATUS_REJECTED.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("ORDER_ALREADY_REJECTED",
                    "Đơn hàng đã bị từ chối duyệt, không thể hủy", "status");
        }

        // Đại lý (cổng B2B) chỉ tự huỷ được đơn của mình khi còn Chờ duyệt / Nháp; đã duyệt thì phải liên hệ NV kinh doanh
        if (CustomerAccess.isCustomer(actor)
                && !SalesOrder.STATUS_PENDING_APPROVAL.equalsIgnoreCase(currentStatus)
                && !SalesOrder.STATUS_DRAFT.equalsIgnoreCase(currentStatus)) {
            throw BusinessException.conflict("CUSTOMER_CANCEL_NOT_ALLOWED",
                    "Đơn đã được duyệt nên đại lý không tự huỷ được. Vui lòng liên hệ nhân viên kinh doanh phụ trách.", "status");
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

        OrderResponse res = orderDraftService.toResponse(saved);
        // S5-01: đại lý tự huỷ trên cổng thì không trả giá sàn / tồn nội bộ
        return res != null && CustomerAccess.isCustomer(actor) ? res.forCustomer() : res;
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
        if (targetStatus != null && SalesOrder.STATUS_CANCELLED.equalsIgnoreCase(targetStatus.trim())) {
            requireAnyRole(actor, CANCEL_ROLES, "Bạn không có quyền huỷ đơn hàng");
            return cancel(id, note, actor);
        }
        return orderDraftService.toResponse(doTransition(id, targetStatus, note, lineDeliveries, null, actor).order());
    }

    /**
     * S6-05: Xác nhận xuất kho đơn đang soạn hàng, cho phép xuất thiếu.
     * Giữ chỗ chuyển thành xuất thực tế (trừ tồn đúng số thực xuất), đơn sang Đã xuất (DISPATCHED);
     * phần còn thiếu tách thành đơn nợ hàng (APPROVED) để giao lần sau. Đã xuất rồi thì không sửa được.
     */
    @Transactional
    public OrderDispatchResponse dispatch(Long id, OrderDispatchRequest request, UserDetailsImpl actor) {
        OrderDispatchRequest req = request != null ? request : new OrderDispatchRequest();
        Transition t = doTransition(id, SalesOrder.STATUS_DISPATCHED, req.getNote(), null, req, actor);
        return new OrderDispatchResponse(orderDraftService.toResponse(t.order()),
                t.backorder() != null ? orderDraftService.toResponse(t.backorder()) : null);
    }

    /** Kết quả chuyển trạng thái: đơn đã lưu và đơn nợ hàng mới (nếu có). */
    record Transition(SalesOrder order, SalesOrder backorder) {
    }

    private Transition doTransition(Long id, String targetStatus, String note,
                                    List<OrderLineDeliveryRequest> lineDeliveries, OrderDispatchRequest dispatchRequest,
                                    UserDetailsImpl actor) {
        if (!StringUtils.hasText(targetStatus)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "STATUS_REQUIRED",
                    "Trạng thái mới không được để trống", "status");
        }
        String cleanTarget = targetStatus.trim().toUpperCase();

        // Huỷ đơn (CANCELLED) đã tách sang cancel(...) ở transitionStatus: bắt buộc lý do & nhả tồn,
        // kiểm quyền như API /cancel để kho không lách quyền huỷ đơn qua API /status.
        if (SalesOrder.STATUS_CANCELLED.equals(cleanTarget)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS_TRANSITION",
                    "Huỷ đơn dùng chức năng Huỷ đơn", "status");
        }
        if (SalesOrder.STATUS_PICKING.equals(cleanTarget) || SalesOrder.STATUS_DISPATCHED.equals(cleanTarget)) {
            requireAnyRole(actor, WAREHOUSE_STEP_ROLES, "Soạn hàng và xuất kho do bộ phận kho thực hiện");
        }

        SalesOrder order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy đơn hàng"));
        CustomerAccess.checkCanAccess(actor, order.getCustomer(), customerRepository);

        String currentStatus = order.getStatus() != null ? order.getStatus().toUpperCase() : "";

        // S4-06: NV kho chỉ soạn / xuất / giao đơn của kho mình được phân công
        assertAssignedWarehouse(actor, order);

        // Kiểm tra chuyển bước hợp lệ
        validateStatusTransition(currentStatus, cleanTarget);

        String cleanNote = note != null ? note.trim() : "";
        if (cleanNote.length() > MAX_COMMENT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG",
                    "Ghi chú tối đa " + MAX_COMMENT + " ký tự", "note");
        }

        // Khi xuất kho (DISPATCHED): trừ tồn thực tế (đúng số thực xuất) và giải phóng giữ chỗ;
        // S6-05: xuất thiếu thì tách phần còn nợ sang đơn nợ hàng
        LocalDateTime now = LocalDateTime.now(clock);
        DispatchResult dispatchResult = null;
        if (SalesOrder.STATUS_DISPATCHED.equals(cleanTarget)) {
            dispatchResult = applyDispatch(order, dispatchRequest, cleanNote, actor);
            order.setDispatchedAt(now);
            order.setDispatchedByUsername(actor != null ? actor.getUsername() : null);
            order.setDispatchNote(StringUtils.hasText(cleanNote) ? cleanNote : null);
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
        if (dispatchResult != null && dispatchResult.shortLineCount() > 0) {
            comment = "Xuất thiếu " + dispatchResult.shortLineCount() + " dòng hàng"
                    + (dispatchResult.backorder() != null
                    ? ", phần còn nợ chuyển sang đơn nợ hàng " + dispatchResult.backorder().getCode()
                    : ", phần còn thiếu không giao nữa")
                    + (StringUtils.hasText(cleanNote) ? ". " + cleanNote : "");
        } else if (StringUtils.hasText(cleanNote)) {
            comment = cleanNote;
        } else if (SalesOrder.STATUS_DELIVERED.equals(cleanTarget) && hasShortageDelivery) {
            comment = "Đại lý đã nhận hàng (có ghi nhận giao thiếu một số dòng hàng)";
        } else {
            comment = defaultActionNote(cleanTarget);
        }

        saveHistory(order, cleanTarget, currentStatus, cleanTarget, comment, actor);
        SalesOrder saved = orderRepository.saveAndFlush(order);
        audit(saved, "CHANGE_STATUS_" + cleanTarget, currentStatus, cleanTarget, comment, actor);
        SalesOrder backorder = null;
        if (dispatchResult != null && dispatchResult.shortLineCount() > 0) {
            // Quy tắc 9: số lượng / tiền trước và sau khi xuất thiếu
            audit(saved, "DISPATCH_SHORTAGE", dispatchResult.before(), quantitySummary(saved), comment, actor);
            backorder = dispatchResult.backorder();
            if (backorder != null) {
                backorder = orderRepository.saveAndFlush(backorder);
                String boComment = "Đơn nợ hàng tách từ đơn " + saved.getCode() + " do kho xuất thiếu";
                saveHistory(backorder, ACTION_BACKORDER_CREATED, null, SalesOrder.STATUS_APPROVED, boComment, actor);
                audit(backorder, "CREATE_BACKORDER", null, quantitySummary(backorder), boComment, actor);
            }
        }

        log.info("S4-06: Đơn hàng {} chuyển trạng thái từ {} sang {}, thực hiện bởi {}",
                saved.getCode(), currentStatus, cleanTarget, actor != null ? actor.getUsername() : "anonymous");

        return new Transition(saved, backorder);
    }

    // ======================= S6-05: XUẤT KHO / XUẤT THIẾU =======================

    static final String ACTION_BACKORDER_CREATED = "BACKORDER_CREATED";
    static final int MAX_BACKORDER_SUFFIX = 99;

    /** Kết quả xuất kho: số dòng xuất thiếu, đơn nợ hàng (chưa lưu), tóm tắt số lượng trước khi tách. */
    record DispatchResult(int shortLineCount, SalesOrder backorder, String before) {
    }

    private record ShortLine(SalesOrderLine line, BigDecimal dispatched, BigDecimal dispatchedBase, String reason) {
    }

    /**
     * Kiểm số thực xuất từng dòng, trừ tồn (InventoryService.dispatchStock), rồi tách phần thiếu.
     * Tiền dòng chia theo tỉ lệ số thực xuất / số đặt; phần còn lại = tổng cũ - phần giữ lại nên tổng 2 đơn đúng bằng đơn cũ.
     */
    private DispatchResult applyDispatch(SalesOrder order, OrderDispatchRequest req, String cleanNote, UserDetailsImpl actor) {
        List<OrderLineDispatchRequest> reqLines = req != null && req.getLines() != null ? req.getLines() : List.of();
        boolean createBackorder = req == null || req.getCreateBackorder() == null || req.getCreateBackorder();

        Map<Long, OrderLineDispatchRequest> byLine = new HashMap<>();
        for (OrderLineDispatchRequest r : reqLines) {
            if (r == null || r.getLineId() == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_LINE_REQUIRED",
                        "Thiếu mã dòng hàng trong danh sách xuất kho", "lines");
            }
            if (byLine.put(r.getLineId(), r) != null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_DISPATCH_LINE",
                        "Dòng hàng " + r.getLineId() + " bị gửi 2 lần", "lines");
            }
            boolean exists = order.getLines().stream().anyMatch(l -> java.util.Objects.equals(l.getId(), r.getLineId()));
            if (!exists) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_LINE_NOT_FOUND",
                        "Không tìm thấy dòng hàng với ID " + r.getLineId(), "lines");
            }
        }

        String before = quantitySummary(order);
        List<InventoryService.DispatchLine> dispatchLines = new ArrayList<>();
        List<ShortLine> shorts = new ArrayList<>();
        boolean anyDispatched = false;
        for (SalesOrderLine line : order.getLines()) {
            OrderLineDispatchRequest r = line.getId() != null ? byLine.get(line.getId()) : null;
            BigDecimal ordered = line.getQuantity();
            BigDecimal dispatched = r != null ? r.getDispatchedQuantity() : ordered;
            if (dispatched == null || dispatched.signum() < 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DISPATCH_QUANTITY",
                        "Dòng " + line.getProductSku() + ": số lượng thực xuất không được để trống hoặc âm", "lines");
            }
            if (dispatched.compareTo(ordered) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPATCH_EXCEEDS_ORDERED",
                        "Dòng " + line.getProductSku() + ": số thực xuất (" + plain(dispatched) + ") vượt số lượng đặt ("
                                + plain(ordered) + " " + line.getUnitName() + ")", "lines");
            }
            BigDecimal lineBase = InventoryService.lineBaseQuantity(line);
            BigDecimal dispatchedBase = dispatched.compareTo(ordered) == 0
                    ? lineBase
                    : dispatched.multiply(factorOf(line)).setScale(4, RoundingMode.HALF_UP).min(lineBase);
            dispatchLines.add(new InventoryService.DispatchLine(line, dispatchedBase));
            if (dispatched.signum() > 0) {
                anyDispatched = true;
            }
            if (dispatched.compareTo(ordered) < 0) {
                String reason = r != null && StringUtils.hasText(r.getShortageReason()) ? r.getShortageReason().trim() : cleanNote;
                if (!StringUtils.hasText(reason)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "DISPATCH_SHORTAGE_REASON_REQUIRED",
                            "Dòng " + line.getProductSku() + " xuất thiếu: bắt buộc nhập lý do", "shortageReason");
                }
                shorts.add(new ShortLine(line, dispatched, dispatchedBase, cut(reason, 255)));
            }
        }
        if (!anyDispatched && !order.getLines().isEmpty()) {
            throw BusinessException.conflict("DISPATCH_NOTHING",
                    "Không có dòng hàng nào được xuất. Nếu không giao được đơn này, hãy huỷ đơn.", "lines");
        }

        Warehouse warehouse = order.getReservedWarehouse();
        if (warehouse == null && order.getCustomer() != null) {
            warehouse = inventoryService.resolveWarehouseForCustomer(order.getCustomer());
        }

        // S6-04: Xử lý lô xuất theo FEFO & trừ tồn lô đã chọn
        for (SalesOrderLine line : order.getLines()) {
            OrderLineDispatchRequest r = line.getId() != null ? byLine.get(line.getId()) : null;
            BigDecimal ordered = line.getQuantity();
            BigDecimal dispatched = r != null ? r.getDispatchedQuantity() : ordered;
            if (dispatched == null || dispatched.signum() <= 0) {
                continue;
            }
            BigDecimal lineBase = InventoryService.lineBaseQuantity(line);
            BigDecimal dispatchedBase = dispatched.compareTo(ordered) == 0
                    ? lineBase
                    : dispatched.multiply(factorOf(line)).setScale(4, RoundingMode.HALF_UP).min(lineBase);

            Product product = line.getProduct();
            if (product != null && Boolean.TRUE.equals(product.getHasBatchManagement()) && warehouse != null) {
                // S6-04 AC1: Thuật toán FEFO gợi ý xuất lô tự động (lô HSD gần nhất còn đủ số lượng)
                FefoLotSuggestionResponse fefo = productLotService.getFefoLotSuggestion(
                        product.getId(), warehouse.getId(), dispatchedBase, actor);
                String suggestedBatch = (fefo != null && fefo.getSuggestedLot() != null)
                        ? fefo.getSuggestedLot().getBatchNumber()
                        : null;
                LocalDate suggestedExp = (fefo != null && fefo.getSuggestedLot() != null)
                        ? fefo.getSuggestedLot().getExpiredDate()
                        : null;
                line.setSuggestedBatchNumber(suggestedBatch);

                String chosenBatch = (r != null && StringUtils.hasText(r.getBatchNumber()))
                        ? r.getBatchNumber().trim()
                        : (StringUtils.hasText(line.getBatchNumber()) ? line.getBatchNumber().trim() : suggestedBatch);

                if (!StringUtils.hasText(chosenBatch)) {
                    throw BusinessException.badRequest("NO_VALID_LOT_AVAILABLE",
                            "Sản phẩm '" + line.getProductSku() + "' quản lý theo lô nhưng không có lô hàng hợp lệ (còn hạn và khả dụng) để xuất kho.");
                }

                String changeReason = (r != null && StringUtils.hasText(r.getLotChangeReason()))
                        ? r.getLotChangeReason().trim()
                        : line.getLotChangeReason();

                if (StringUtils.hasText(chosenBatch)) {
                    // S6-04 AC2: Thủ kho chọn lô khác được nhưng phải nhập lý do
                    boolean isDifferent = suggestedBatch != null && !suggestedBatch.equalsIgnoreCase(chosenBatch);
                    if (isDifferent) {
                        if (!StringUtils.hasText(changeReason) || changeReason.trim().length() < 5) {
                            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOT_CHANGE_REASON_REQUIRED",
                                    "Dòng hàng '" + line.getProductName() + "' (" + line.getProductSku()
                                            + "): chọn xuất lô '" + chosenBatch + "' khác với lô gợi ý FEFO ('"
                                            + suggestedBatch + "'). Bắt buộc nhập lý do đổi lô (tối thiểu 5 ký tự).", "lines");
                        }
                        line.setLotChangeReason(changeReason.trim());
                    } else {
                        line.setLotChangeReason(null);
                    }

                    // S6-04 AC3: Xuất kho trừ đúng tồn của lô đã chọn
                    ProductLot lotDeducted = productLotService.deductLotStock(
                            product.getId(), warehouse.getId(), chosenBatch, dispatchedBase,
                            StockMovement.DOC_SALES_ORDER, order.getId(), order.getCode(), actor);

                    line.setBatchNumber(chosenBatch);
                    if (lotDeducted != null) {
                        line.setLotExpiredDate(lotDeducted.getExpiredDate());
                    } else if (suggestedExp != null && chosenBatch.equalsIgnoreCase(suggestedBatch)) {
                        line.setLotExpiredDate(suggestedExp);
                    }
                }
            }
        }

        // Trừ tồn + giải phóng giữ chỗ khi dòng còn số lượng gốc (phải gọi trước khi tách dòng)
        inventoryService.dispatchStock(order, dispatchLines, actor);
        if (shorts.isEmpty()) {
            return new DispatchResult(0, null, before);
        }

        SalesOrder backorder = createBackorder ? newBackorder(order, actor) : null;
        int lineNo = 1;
        for (ShortLine sl : shorts) {
            SalesOrderLine line = sl.line();
            BigDecimal ordered = line.getQuantity();
            BigDecimal remaining = ordered.subtract(sl.dispatched());
            BigDecimal origBase = InventoryService.lineBaseQuantity(line);
            BigDecimal gross = nz(line.getGrossAmount());
            BigDecimal discount = nz(line.getDiscountAmount());
            BigDecimal keepGross = prorate(gross, sl.dispatched(), ordered);
            BigDecimal keepDiscount = prorate(discount, sl.dispatched(), ordered);
            BigDecimal restGross = gross.subtract(keepGross);
            BigDecimal restDiscount = discount.subtract(keepDiscount);

            if (line.getOrderedQuantity() == null) {
                line.setOrderedQuantity(ordered);
            }
            line.setQuantity(sl.dispatched());
            line.setBaseQuantity(sl.dispatchedBase());
            line.setGrossAmount(keepGross);
            line.setDiscountAmount(keepDiscount);
            line.setNetAmount(net(keepGross, keepDiscount));
            line.setDispatchShortageReason(sl.reason());
            line.setBackorderQuantity(backorder != null ? remaining : null);

            if (backorder != null) {
                backorder.addLine(SalesOrderLine.builder()
                        .lineNo(lineNo++)
                        .product(line.getProduct())
                        .productSku(line.getProductSku())
                        .productName(line.getProductName())
                        .unitName(line.getUnitName())
                        .conversionFactor(line.getConversionFactor())
                        .quantity(remaining)
                        .baseUnit(line.getBaseUnit())
                        .baseQuantity(origBase.subtract(sl.dispatchedBase()))
                        .priceList(line.getPriceList())
                        .unitPrice(line.getUnitPrice())
                        .pricePerUnit(line.getPricePerUnit())
                        .isCustomPrice(line.getIsCustomPrice())
                        .floorPrice(line.getFloorPrice())
                        .grossAmount(restGross)
                        .discountPolicyCode(line.getDiscountPolicyCode())
                        .discountAmount(restDiscount)
                        .netAmount(net(restGross, restDiscount))
                        .build());
            }
        }
        recalcTotals(order);
        if (backorder != null) {
            recalcTotals(backorder);
        }
        return new DispatchResult(shorts.size(), backorder, before);
    }

    /** Đơn nợ hàng: đã duyệt sẵn (tiền đã tính công nợ từ đơn gốc), không giữ chỗ tồn, cùng kho với đơn gốc. */
    private SalesOrder newBackorder(SalesOrder parent, UserDetailsImpl actor) {
        String parentNote = "Đơn nợ hàng của đơn " + parent.getCode();
        return SalesOrder.builder()
                .code(backorderCode(parent))
                .customer(parent.getCustomer())
                .deliveryAddress(parent.getDeliveryAddress())
                .status(SalesOrder.STATUS_APPROVED)
                .note(parentNote)
                .source(parent.getSource() != null ? parent.getSource() : SalesOrder.SOURCE_STAFF)
                // Giữ mốc duyệt của đơn gốc: hạn nợ và doanh số tính như đơn gốc
                .approvedAt(parent.getApprovedAt())
                .approvedById(parent.getApprovedById())
                .approvedByUsername(parent.getApprovedByUsername())
                .submittedAt(parent.getSubmittedAt())
                .submittedByUsername(parent.getSubmittedByUsername())
                .lastApprovalComment("Tách từ đơn " + parent.getCode() + " do kho xuất thiếu")
                .reservedWarehouse(parent.getReservedWarehouse())
                .reservationStatus(SalesOrder.RESERVATION_RELEASED)
                .backorder(Boolean.TRUE)
                .parentOrder(parent)
                .parentOrderCode(parent.getCode())
                .paymentMethod(parent.getPaymentMethod() != null ? parent.getPaymentMethod() : SalesOrder.PAYMENT_METHOD_CREDIT)
                .createdById(actor != null ? actor.getId() : null)
                .createdByUsername(actor != null ? actor.getUsername() : null)
                .build();
    }

    /** Mã đơn nợ hàng: mã đơn gốc + "-N1", "-N2"... (đơn nợ của đơn nợ vẫn đánh tiếp theo mã gốc). */
    String backorderCode(SalesOrder parent) {
        String base = parent.getCode() != null ? parent.getCode().replaceFirst("-N\\d+$", "") : "DH";
        if (base.length() > 26) {
            base = base.substring(0, 26);
        }
        for (int k = 1; k <= MAX_BACKORDER_SUFFIX; k++) {
            String code = base + "-N" + k;
            if (!orderRepository.existsByCode(code)) {
                return code;
            }
        }
        throw BusinessException.conflict("BACKORDER_CODE_EXHAUSTED",
                "Đơn " + parent.getCode() + " đã tách quá nhiều đơn nợ hàng", "code");
    }

    private static void recalcTotals(SalesOrder order) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        for (SalesOrderLine l : order.getLines()) {
            subtotal = subtotal.add(nz(l.getGrossAmount()));
            discountTotal = discountTotal.add(nz(l.getDiscountAmount()));
        }
        order.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        order.setDiscountTotal(discountTotal.setScale(2, RoundingMode.HALF_UP));
        order.setTotalAmount(subtotal.subtract(discountTotal).setScale(2, RoundingMode.HALF_UP));
    }

    /** amount * part / whole, làm tròn 2 số lẻ. */
    static BigDecimal prorate(BigDecimal amount, BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.multiply(part).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal net(BigDecimal gross, BigDecimal discount) {
        return gross.subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal factorOf(SalesOrderLine line) {
        return line.getConversionFactor() != null && line.getConversionFactor().signum() > 0
                ? line.getConversionFactor() : BigDecimal.ONE;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    /** "SKU=SL ĐVT; ...; Tổng=..." để ghi nhật ký trước / sau. */
    static String quantitySummary(SalesOrder order) {
        StringBuilder sb = new StringBuilder();
        for (SalesOrderLine l : order.getLines()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(l.getProductSku()).append('=').append(plain(l.getQuantity())).append(' ').append(l.getUnitName());
        }
        sb.append("; Tổng=").append(plain(order.getTotalAmount()));
        return sb.toString();
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
        assertAssignedWarehouse(actor, order);

        // S5-01: chỉ sửa số thực giao khi đơn đã xuất kho / đã giao. Đơn đã Đóng hoặc đã Huỷ thì khoá (chứng từ đã chốt).
        String status = order.getStatus() != null ? order.getStatus().toUpperCase() : "";
        if (!SalesOrder.STATUS_DISPATCHED.equals(status) && !SalesOrder.STATUS_DELIVERED.equals(status)) {
            throw BusinessException.conflict("DELIVERY_NOT_EDITABLE",
                    "Chỉ sửa số lượng thực giao khi đơn đã xuất kho hoặc đã giao (đơn hiện ở trạng thái " + status + ")",
                    "status");
        }
        // S6-08: đã xác nhận giao (biên bản đã ký, hàng không giao được đã nhập lại kho) thì khoá số thực giao
        if (order.getDeliveryConfirmedAt() != null) {
            throw BusinessException.conflict("DELIVERY_CONFIRMED_LOCKED",
                    "Đơn đã xác nhận giao hàng theo biên bản, không sửa số lượng thực giao được", "status");
        }
        String before = deliverySummary(order);

        applyLineDeliveries(order, lineDeliveries);

        SalesOrder saved = orderRepository.saveAndFlush(order);
        // Quy tắc 9: ghi nhật ký số thực giao trước / sau
        audit(saved, "UPDATE_DELIVERED_QUANTITY", before, deliverySummary(saved),
                "Cập nhật số lượng thực giao đơn " + saved.getCode(), actor);
        log.info("S5-01: Đã cập nhật số lượng thực giao cho đơn hàng {}, thực hiện bởi {}",
                saved.getCode(), actor != null ? actor.getUsername() : "anonymous");

        return orderDraftService.toResponse(saved);
    }

    static void applyLineDeliveries(SalesOrder order, List<OrderLineDeliveryRequest> lineDeliveries) {
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
            // Không giao nhiều hơn số đã đặt / đã xuất kho
            if (targetLine.getQuantity() != null && req.getDeliveredQuantity().compareTo(targetLine.getQuantity()) > 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERED_EXCEEDS_ORDERED",
                        "Dòng " + targetLine.getProductSku() + ": số thực giao ("
                                + req.getDeliveredQuantity().stripTrailingZeros().toPlainString() + ") vượt số lượng đặt ("
                                + targetLine.getQuantity().stripTrailingZeros().toPlainString() + " " + targetLine.getUnitName() + ")",
                        "deliveredQuantity");
            }

            targetLine.setDeliveredQuantity(req.getDeliveredQuantity());
            if (StringUtils.hasText(req.getShortageReason())) {
                targetLine.setShortageReason(req.getShortageReason().trim());
            } else if (req.getDeliveredQuantity().compareTo(targetLine.getQuantity()) >= 0) {
                targetLine.setShortageReason(null);
            }
        }
    }

    static String deliverySummary(SalesOrder order) {
        StringBuilder sb = new StringBuilder();
        for (SalesOrderLine l : order.getLines()) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(l.getProductSku()).append('=')
                    .append(l.getDeliveredQuantity() != null ? l.getDeliveredQuantity().stripTrailingZeros().toPlainString() : "-");
        }
        return sb.toString();
    }

    /**
     * S4-06: NV kho (không phải QL kho / Admin / QL kinh doanh) chỉ thao tác đơn của kho mình được gắn.
     * Đơn chưa xác định kho thì bỏ qua.
     */
    private void assertAssignedWarehouse(UserDetailsImpl actor, SalesOrder order) {
        assertAssignedWarehouse(actor, order, userRepository);
    }

    /** Dùng chung cho xác nhận giao hàng (S6-08). */
    static void assertAssignedWarehouse(UserDetailsImpl actor, SalesOrder order,
                                        com.erp.backend.repository.UserRepository userRepository) {
        if (actor == null || actor.getAuthorities() == null || order.getReservedWarehouse() == null) {
            return;
        }
        Set<String> roles = new java.util.HashSet<>();
        actor.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (!roles.contains("ROLE_WAREHOUSE") || roles.contains("ROLE_WH_MANAGER") || roles.contains("ROLE_ADMIN")
                || roles.contains("ROLE_SALES_MANAGER")) {
            return;
        }
        Long whId = order.getReservedWarehouse().getId();
        boolean assigned = userRepository.findById(actor.getId())
                .map(u -> u.getWarehouses().stream().anyMatch(w -> java.util.Objects.equals(w.getId(), whId)))
                .orElse(false);
        if (!assigned) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ORDER_WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công kho " + order.getReservedWarehouse().getName()
                            + " nên không xử lý được đơn " + order.getCode(), "status");
        }
    }

    static void requireAnyRole(UserDetailsImpl actor, Set<String> roles, String message) {
        boolean ok = actor != null && actor.getAuthorities() != null
                && actor.getAuthorities().stream().anyMatch(a -> roles.contains(a.getAuthority()));
        if (!ok) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "STATUS_CHANGE_NOT_ALLOWED", message, "status");
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
        saveHistory(approvalRepository, order, action, fromStatus, toStatus, comment, actor);
    }

    /** Dùng chung cho xác nhận giao hàng (S6-08). */
    static void saveHistory(SalesOrderApprovalRepository approvalRepository, SalesOrder order, String action,
                            String fromStatus, String toStatus, String comment, UserDetailsImpl actor) {
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
        audit(auditLogService, order, action, fromStatus, toStatus, comment, actor);
    }

    /** Dùng chung cho xác nhận giao hàng (S6-08). */
    static void audit(AuditLogService auditLogService, SalesOrder order, String action, String fromStatus,
                      String toStatus, String comment, UserDetailsImpl actor) {
        // Cột lý do của nhật ký tối đa 500 ký tự (ghi ở transaction riêng, quá dài sẽ lỗi cả thao tác)
        auditLogService.record(AuditModule.ORDER, action, "SALES_ORDER", order.getId(), order.getCode(),
                fromStatus, toStatus, cut(comment, MAX_COMMENT), actor);
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
