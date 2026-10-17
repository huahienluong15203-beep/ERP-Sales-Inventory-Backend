package com.erp.backend.service;

import com.erp.backend.dto.audit.CreateAuditLogEntry;
import com.erp.backend.dto.stockadjustment.*;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import jakarta.persistence.criteria.Predicate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * S8-03: Dịch vụ lập và phê duyệt phiếu điều chỉnh tồn kho.
 * - API Audit tồn kho ngầm, bắt buộc có ID QL.
 * - Phân quyền độc lập giữa người lập và người duyệt.
 * - Bắt buộc chọn lý do và ghi rõ giá trị tổn thất.
 * - Chỉ Quản lý kho được duyệt; người lập và người duyệt không được là một.
 * - Mọi phiếu điều chỉnh đều xuất hiện trên thẻ kho.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAdjustmentService {

    private final StockAdjustmentRepository stockAdjustmentRepository;
    private final StockAdjustmentLineRepository stockAdjustmentLineRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductRepository productRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductLotRepository productLotRepository;
    private final StockMovementRepository stockMovementRepository;
    private final AuditLogService auditLogService;

    Clock clock = Clock.system(ZoneId.of("Asia/Ho_Chi_Minh"));

    private static final Map<String, String> REASON_LABELS = Map.of(
            StockAdjustment.REASON_DAMAGED_GOODS, "Hàng hỏng / hư hại",
            StockAdjustment.REASON_LOST_GOODS, "Hàng mất / thất thoát kho",
            StockAdjustment.REASON_EXPIRED_DISPOSAL, "Hàng hết hạn phải huỷ",
            StockAdjustment.REASON_OTHER, "Lý do khác"
    );

    private static final Map<String, String> STATUS_LABELS = Map.of(
            StockAdjustment.STATUS_PENDING_APPROVAL, "Chờ duyệt",
            StockAdjustment.STATUS_APPROVED, "Đã duyệt & xuất kho",
            StockAdjustment.STATUS_REJECTED, "Từ chối",
            StockAdjustment.STATUS_CANCELLED, "Đã huỷ"
    );

    /**
     * Tiêu chí 1 & 2: Lập phiếu điều chỉnh tồn kho.
     * - Dùng cho hàng hỏng, hàng mất, hàng hết hạn phải huỷ.
     * - Bắt buộc chọn lý do và tính rõ giá trị tổn thất (dựa trên giá vốn).
     * - Trạng thái ban đầu: PENDING_APPROVAL. Chưa thay đổi tồn kho thực tế.
     */
    @Transactional
    public StockAdjustmentResponse createAdjustment(StockAdjustmentCreateRequest req, UserDetailsImpl actor) {
        if (!StringUtils.hasText(req.getAdjustmentReason())) {
            throw BusinessException.badRequest("Bắt buộc phải chọn lý do điều chỉnh tồn kho.");
        }

        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng ID: " + req.getWarehouseId()));

        if (!"ACTIVE".equalsIgnoreCase(warehouse.getStatus())) {
            throw BusinessException.badRequest("Kho hàng " + warehouse.getName() + " đang ngừng hoạt động.");
        }

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw BusinessException.badRequest("Phiếu điều chỉnh phải có ít nhất một dòng mặt hàng.");
        }

        LocalDate adjDate = req.getAdjustmentDate() != null ? req.getAdjustmentDate() : LocalDate.now(clock);
        String code = generateAdjustmentCode(adjDate);

        StockAdjustment adjustment = StockAdjustment.builder()
                .code(code)
                .adjustmentDate(adjDate)
                .warehouse(warehouse)
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .adjustmentReason(req.getAdjustmentReason())
                .reasonDescription(req.getReasonDescription())
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .notes(req.getNotes())
                .createdById(actor != null ? actor.getId() : null)
                .createdByName(actor != null ? actor.getUsername() : "system")
                .build();

        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        int lineNo = 1;

        for (StockAdjustmentItemRequest itemReq : req.getItems()) {
            Product product = productRepository.findById(itemReq.getProductId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm ID: " + itemReq.getProductId()));

            BigDecimal adjQty = itemReq.getAdjustmentQuantity();
            if (adjQty == null || adjQty.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest("Số lượng điều chỉnh của sản phẩm " + product.getSku() + " phải lớn hơn 0.");
            }

            // Kiểm tra tồn kho hiện tại
            Inventory inventory = inventoryRepository.findByWarehouse_IdAndProduct_Id(warehouse.getId(), product.getId())
                    .orElse(null);
            BigDecimal currentStock = inventory != null && inventory.getPhysicalStock() != null
                    ? inventory.getPhysicalStock()
                    : BigDecimal.ZERO;

            if (adjQty.compareTo(currentStock) > 0) {
                throw BusinessException.badRequest(String.format(
                        "Số lượng điều chỉnh giảm (%s) vượt quá tồn kho thực tế hiện tại (%s) của sản phẩm %s tại kho %s.",
                        adjQty.stripTrailingZeros().toPlainString(),
                        currentStock.stripTrailingZeros().toPlainString(),
                        product.getSku(),
                        warehouse.getName()
                ));
            }

            // Kiểm tra lô nếu sản phẩm quản lý theo lô
            boolean hasBatch = Boolean.TRUE.equals(product.getHasBatchManagement());
            Long productLotId = null;
            if (hasBatch) {
                if (!StringUtils.hasText(itemReq.getBatchNumber())) {
                    throw BusinessException.badRequest("Mặt hàng " + product.getSku() + " quản lý theo lô, bắt buộc phải chọn hoặc nhập số lô sản xuất.");
                }
                String cleanBatch = itemReq.getBatchNumber().trim();
                ProductLot lot = productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(
                        product.getId(), warehouse.getId(), cleanBatch).orElse(null);
                if (lot == null) {
                    // Hỗ trợ nhập số lô thủ công: tự động tạo ProductLot với số lượng bằng số lượng giảm
                    lot = ProductLot.builder()
                            .product(product)
                            .warehouse(warehouse)
                            .batchNumber(cleanBatch)
                            .quantity(adjQty)
                            .status("ACTIVE")
                            .notes("Lô hàng nhập thủ công qua phiếu điều chỉnh tồn kho")
                            .build();
                    lot = productLotRepository.save(lot);
                } else if (lot.getQuantity() != null && lot.getQuantity().compareTo(adjQty) < 0) {
                    // Nếu lô tồn tại nhưng số lượng ghi nhận trên hệ thống nhỏ hơn lượng hư hỏng thực tế,
                    // cập nhật tồn của lô để đáp ứng số lượng điều chỉnh giảm
                    lot.setQuantity(adjQty);
                    productLotRepository.save(lot);
                }
                productLotId = lot.getId();
            }

            // Tính giá trị tổn thất = số lượng giảm * giá vốn (costPrice)
            BigDecimal costPrice = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
            BigDecimal lineLoss = adjQty.multiply(costPrice);

            StockAdjustmentLine line = StockAdjustmentLine.builder()
                    .lineNo(lineNo++)
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .baseUnit(product.getBaseUnit())
                    .currentStock(currentStock)
                    .adjustmentQuantity(adjQty)
                    .costPrice(costPrice)
                    .lossValue(lineLoss)
                    .hasBatchManagement(hasBatch)
                    .batchNumber(itemReq.getBatchNumber())
                    .productLotId(productLotId)
                    .lineNotes(itemReq.getLineNotes())
                    .build();

            adjustment.addLine(line);
            totalQty = totalQty.add(adjQty);
            totalLoss = totalLoss.add(lineLoss);
        }

        adjustment.setTotalQuantity(totalQty);
        adjustment.setTotalLossValue(totalLoss);

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);
        log.info("S8-03: Lập phiếu điều chỉnh tồn kho thành công: code={}, kho={}, lý do={}, tổn thất={} đ",
                saved.getCode(), warehouse.getCode(), saved.getAdjustmentReason(), totalLoss);

        return toResponse(saved);
    }

    /**
     * Quản lý kho phê duyệt phiếu điều chỉnh tồn kho.
     * - Chỉ Quản lý kho / Admin được duyệt.
     * - Người lập và người duyệt KHÔNG ĐƯỢC LÀ MỘT.
     * - Trừ tồn kho thực tế, trừ tồn lô tương ứng.
     * - Mọi phiếu điều chỉnh đều xuất hiện trên thẻ kho (StockMovement).
     * - Ghi AuditLog ngầm bắt buộc có ID QL.
     */
    @Transactional
    public StockAdjustmentResponse approveAdjustment(Long adjustmentId, StockAdjustmentDecisionRequest decision, UserDetailsImpl actor) {
        if (actor == null || actor.getId() == null) {
            throw BusinessException.badRequest("Bắt buộc phải có thông tin Quản lý kho thực hiện phê duyệt (ID QL).");
        }

        StockAdjustment adjustment = stockAdjustmentRepository.findByIdForUpdate(adjustmentId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu điều chỉnh tồn kho ID: " + adjustmentId));

        if (!StockAdjustment.STATUS_PENDING_APPROVAL.equals(adjustment.getStatus())) {
            throw BusinessException.badRequest("Phiếu điều chỉnh tồn kho " + adjustment.getCode() + " không ở trạng thái chờ duyệt (hiện tại: " + adjustment.getStatus() + ").");
        }

        // Quy tắc phân quyền: Người lập và người duyệt KHÔNG ĐƯỢC LÀ MỘT
        if (actor.getId().equals(adjustment.getCreatedById())) {
            throw BusinessException.badRequest("Người lập và người duyệt không được là một người. Yêu cầu Quản lý kho khác phê duyệt độc lập.");
        }

        Warehouse warehouse = adjustment.getWarehouse();
        LocalDateTime now = LocalDateTime.now(clock);

        log.info("S8-03: Quản lý kho (ID: {}, User: {}) bắt đầu phê duyệt phiếu điều chỉnh {}: kho={}, số dòng={}, tổng tổn thất={}",
                actor.getId(), actor.getUsername(), adjustment.getCode(), warehouse.getCode(), adjustment.getLines().size(), adjustment.getTotalLossValue());

        for (StockAdjustmentLine line : adjustment.getLines()) {
            Product product = line.getProduct();
            BigDecimal adjQty = line.getAdjustmentQuantity();

            // 1. Trừ tồn kho thực tế (Inventory)
            Inventory inventory = inventoryRepository.findByWarehouseIdAndProductIdForUpdate(warehouse.getId(), product.getId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy thông tin tồn kho của sản phẩm " + product.getSku()));

            BigDecimal oldPhysical = inventory.getPhysicalStock() != null ? inventory.getPhysicalStock() : BigDecimal.ZERO;
            if (oldPhysical.compareTo(adjQty) < 0) {
                throw BusinessException.badRequest(String.format(
                        "Tồn kho thực tế của sản phẩm %s tại kho %s (%s) không đủ để điều chỉnh giảm (%s).",
                        product.getSku(), warehouse.getName(), oldPhysical, adjQty
                ));
            }

            BigDecimal newPhysical = oldPhysical.subtract(adjQty);
            inventory.setPhysicalStock(newPhysical);
            inventoryRepository.save(inventory);

            // 2. Trừ tồn trong lô nếu sản phẩm có quản lý lô
            if (Boolean.TRUE.equals(line.getHasBatchManagement()) && StringUtils.hasText(line.getBatchNumber())) {
                String cleanBatch = line.getBatchNumber().trim();
                ProductLot lot = productLotRepository.findByProductAndWarehouseAndBatchNumberForUpdate(
                        product.getId(), warehouse.getId(), cleanBatch)
                        .orElse(null);

                if (lot != null) {
                    BigDecimal oldLotQty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
                    if (oldLotQty.compareTo(adjQty) < 0) {
                        lot.setQuantity(BigDecimal.ZERO);
                    } else {
                        lot.setQuantity(oldLotQty.subtract(adjQty));
                    }
                    productLotRepository.save(lot);
                }
            }

            // 3. Tiêu chí 4: Mọi phiếu điều chỉnh đều xuất hiện trên thẻ kho (StockMovement)
            String reasonLabel = REASON_LABELS.getOrDefault(adjustment.getAdjustmentReason(), adjustment.getAdjustmentReason());
            StockMovement movement = StockMovement.builder()
                    .warehouse(warehouse)
                    .warehouseCode(warehouse.getCode())
                    .product(product)
                    .productSku(product.getSku())
                    .productName(product.getName())
                    .baseUnit(product.getBaseUnit())
                    .movementType(StockMovement.TYPE_STOCK_ADJUSTMENT)
                    .quantity(adjQty.negate()) // Dấu âm biểu thị giảm tồn do hỏng, mất, huỷ
                    .balanceAfter(newPhysical)
                    .docType(StockMovement.DOC_STOCK_ADJUSTMENT)
                    .docId(adjustment.getId())
                    .docCode(adjustment.getCode())
                    .note(String.format("Điều chỉnh giảm tồn kho theo phiếu %s: %s (Tổn thất: %s)",
                            adjustment.getCode(), reasonLabel, formatVnd(line.getLossValue())))
                    .actorId(actor.getId())
                    .actorUsername(actor.getUsername())
                    .movementAt(now)
                    .build();

            stockMovementRepository.save(movement);
        }

        // Cập nhật trạng thái phiếu
        adjustment.setStatus(StockAdjustment.STATUS_APPROVED);
        adjustment.setApprovedById(actor.getId());
        adjustment.setApprovedByName(actor.getUsername());
        adjustment.setApprovedAt(now);
        if (decision != null && StringUtils.hasText(decision.getNotes())) {
            adjustment.setNotes(adjustment.getNotes() != null
                    ? adjustment.getNotes() + " | Ý kiến duyệt: " + decision.getNotes()
                    : "Ý kiến duyệt: " + decision.getNotes());
        }

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);

        // Ghi AuditLog ngầm bắt buộc có ID QL
        try {
            String reasonLabel = REASON_LABELS.getOrDefault(saved.getAdjustmentReason(), saved.getAdjustmentReason());
            CreateAuditLogEntry auditEntry = CreateAuditLogEntry.builder()
                    .module(AuditModule.INVENTORY)
                    .action("APPROVE_STOCK_ADJUSTMENT")
                    .actorId(actor.getId()) // Bắt buộc có ID QL
                    .actorUsername(actor.getUsername())
                    .targetType("STOCK_ADJUSTMENT")
                    .targetId(saved.getId())
                    .targetCode(saved.getCode())
                    .reason(String.format("Quản lý kho duyệt phiếu điều chỉnh tồn kho %s: kho=%s, lý do=%s, tổng tổn thất=%s",
                            saved.getCode(), warehouse.getCode(), reasonLabel, formatVnd(saved.getTotalLossValue())))
                    .build();
            auditLogService.record(auditEntry);
            log.info("S8-03: Đã ghi AuditLog ngầm duyệt điều chỉnh tồn kho thành công với ID QL={}", actor.getId());
        } catch (Exception ex) {
            log.warn("S8-03: Không thể ghi AuditLog ngầm: {}", ex.getMessage());
        }

        return toResponse(saved);
    }

    /**
     * Quản lý kho từ chối duyệt phiếu điều chỉnh tồn kho.
     * - Người duyệt không được là người lập phiếu.
     * - Ghi AuditLog ngầm với ID QL.
     */
    @Transactional
    public StockAdjustmentResponse rejectAdjustment(Long adjustmentId, StockAdjustmentDecisionRequest decision, UserDetailsImpl actor) {
        if (actor == null || actor.getId() == null) {
            throw BusinessException.badRequest("Bắt buộc phải có thông tin Quản lý kho thực hiện (ID QL).");
        }

        StockAdjustment adjustment = stockAdjustmentRepository.findByIdForUpdate(adjustmentId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu điều chỉnh tồn kho ID: " + adjustmentId));

        if (!StockAdjustment.STATUS_PENDING_APPROVAL.equals(adjustment.getStatus())) {
            throw BusinessException.badRequest("Phiếu điều chỉnh tồn kho " + adjustment.getCode() + " không ở trạng thái chờ duyệt.");
        }

        // Người lập và người duyệt không được là một
        if (actor.getId().equals(adjustment.getCreatedById())) {
            throw BusinessException.badRequest("Người lập và người từ chối không được là một người.");
        }

        adjustment.setStatus(StockAdjustment.STATUS_REJECTED);
        String rejectReason = decision != null ? decision.getNotes() : null;
        adjustment.setRejectionReason(rejectReason);
        adjustment.setApprovedById(actor.getId());
        adjustment.setApprovedByName(actor.getUsername());
        adjustment.setApprovedAt(LocalDateTime.now(clock));

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);

        // Ghi AuditLog ngầm từ chối
        try {
            CreateAuditLogEntry auditEntry = CreateAuditLogEntry.builder()
                    .module(AuditModule.INVENTORY)
                    .action("REJECT_STOCK_ADJUSTMENT")
                    .actorId(actor.getId())
                    .actorUsername(actor.getUsername())
                    .targetType("STOCK_ADJUSTMENT")
                    .targetId(saved.getId())
                    .targetCode(saved.getCode())
                    .reason(String.format("Quản lý kho từ chối duyệt phiếu điều chỉnh %s: lý do từ chối=%s",
                            saved.getCode(), rejectReason))
                    .build();
            auditLogService.record(auditEntry);
        } catch (Exception ex) {
            log.warn("S8-03: Lỗi ghi audit log từ chối: {}", ex.getMessage());
        }

        return toResponse(saved);
    }

    /**
     * Huỷ phiếu điều chỉnh tồn kho (chỉ huỷ khi đang chờ duyệt PENDING_APPROVAL).
     */
    @Transactional
    public StockAdjustmentResponse cancelAdjustment(Long adjustmentId, String cancelReason, UserDetailsImpl actor) {
        StockAdjustment adjustment = stockAdjustmentRepository.findByIdForUpdate(adjustmentId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu điều chỉnh tồn kho ID: " + adjustmentId));

        if (!StockAdjustment.STATUS_PENDING_APPROVAL.equals(adjustment.getStatus())) {
            throw BusinessException.badRequest("Không thể huỷ phiếu điều chỉnh tồn kho ở trạng thái " + adjustment.getStatus());
        }

        adjustment.setStatus(StockAdjustment.STATUS_CANCELLED);
        if (StringUtils.hasText(cancelReason)) {
            adjustment.setNotes(adjustment.getNotes() != null
                    ? adjustment.getNotes() + " | Lý do huỷ: " + cancelReason
                    : "Lý do huỷ: " + cancelReason);
        }

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public StockAdjustmentResponse getAdjustmentDetail(Long id) {
        StockAdjustment adjustment = stockAdjustmentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu điều chỉnh tồn kho ID: " + id));
        return toResponse(adjustment);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentResponse> searchAdjustments(
            Long warehouseId, String status, String reason,
            LocalDate fromDate, LocalDate toDate, String keyword,
            int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));

        Specification<StockAdjustment> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (warehouseId != null) {
                ps.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
                ps.add(cb.equal(root.get("status"), status.trim()));
            }
            if (StringUtils.hasText(reason) && !"ALL".equalsIgnoreCase(reason.trim())) {
                ps.add(cb.equal(root.get("adjustmentReason"), reason.trim()));
            }
            if (fromDate != null) {
                ps.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("adjustmentDate"), fromDate));
            }
            if (toDate != null) {
                ps.add(cb.lessThanOrEqualTo(root.<LocalDate>get("adjustmentDate"), toDate));
            }
            if (StringUtils.hasText(keyword)) {
                String kw = "%" + keyword.trim().toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("code")), kw),
                        cb.like(cb.lower(root.get("warehouseName")), kw),
                        cb.like(cb.lower(root.get("createdByName")), kw)
                ));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<StockAdjustment> pageResult = stockAdjustmentRepository.findAll(spec, pageable);

        List<StockAdjustmentResponse> content = pageResult.getContent().stream()
                .map(this::toResponse)
                .toList();

        return new PageResponse<>(content, pageResult.getNumber(), pageResult.getSize(),
                pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    private synchronized String generateAdjustmentCode(LocalDate date) {
        String yearMonth = date.format(DateTimeFormatter.ofPattern("yyyyMM"));
        String prefix = "DC-" + yearMonth + "-";

        List<String> existing = stockAdjustmentRepository.findCodesByPrefix(prefix);
        int maxSeq = 0;
        for (String c : existing) {
            try {
                String suffix = c.substring(prefix.length());
                int seq = Integer.parseInt(suffix);
                if (seq > maxSeq) maxSeq = seq;
            } catch (Exception ignored) {
            }
        }

        return String.format("%s%03d", prefix, maxSeq + 1);
    }

    private StockAdjustmentResponse toResponse(StockAdjustment a) {
        List<StockAdjustmentLineResponse> lines = a.getLines().stream()
                .map(l -> StockAdjustmentLineResponse.builder()
                        .id(l.getId())
                        .lineNo(l.getLineNo())
                        .productId(l.getProduct() != null ? l.getProduct().getId() : null)
                        .productSku(l.getProductSku())
                        .productName(l.getProductName())
                        .baseUnit(l.getBaseUnit())
                        .currentStock(l.getCurrentStock())
                        .adjustmentQuantity(l.getAdjustmentQuantity())
                        .costPrice(l.getCostPrice())
                        .lossValue(l.getLossValue())
                        .hasBatchManagement(l.getHasBatchManagement())
                        .batchNumber(l.getBatchNumber())
                        .productLotId(l.getProductLotId())
                        .lineNotes(l.getLineNotes())
                        .build())
                .toList();

        return StockAdjustmentResponse.builder()
                .id(a.getId())
                .code(a.getCode())
                .adjustmentDate(a.getAdjustmentDate())
                .warehouseId(a.getWarehouse() != null ? a.getWarehouse().getId() : null)
                .warehouseCode(a.getWarehouseCode())
                .warehouseName(a.getWarehouseName())
                .adjustmentReason(a.getAdjustmentReason())
                .adjustmentReasonLabel(a.getAdjustmentReason() != null ? REASON_LABELS.getOrDefault(a.getAdjustmentReason(), a.getAdjustmentReason()) : "")
                .reasonDescription(a.getReasonDescription())
                .status(a.getStatus())
                .statusLabel(a.getStatus() != null ? STATUS_LABELS.getOrDefault(a.getStatus(), a.getStatus()) : "")
                .totalQuantity(a.getTotalQuantity())
                .totalLossValue(a.getTotalLossValue())
                .rejectionReason(a.getRejectionReason())
                .notes(a.getNotes())
                .createdById(a.getCreatedById())
                .createdByName(a.getCreatedByName())
                .approvedById(a.getApprovedById())
                .approvedByName(a.getApprovedByName())
                .approvedAt(a.getApprovedAt())
                .createdAt(a.getCreatedAt())
                .updatedAt(a.getUpdatedAt())
                .lines(lines)
                .build();
    }

    @Transactional(readOnly = true)
    public Map<Long, BigDecimal> getProductCosts(List<Long> productIds) {
        List<Product> products;
        if (productIds == null || productIds.isEmpty()) {
            products = productRepository.findAll();
        } else {
            products = productRepository.findAllById(productIds);
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Product p : products) {
            if (p != null && p.getId() != null) {
                result.put(p.getId(), p.getCostPrice() != null ? p.getCostPrice() : BigDecimal.ZERO);
            }
        }
        return result;
    }

    private static String formatVnd(BigDecimal amount) {
        if (amount == null) return "0 đ";
        DecimalFormat df = new DecimalFormat("#,### đ", new DecimalFormatSymbols(Locale.GERMANY));
        return df.format(amount);
    }
}
