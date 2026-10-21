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
import org.springframework.http.HttpStatus;
import org.springframework.security.core.GrantedAuthority;
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
import java.util.stream.Collectors;

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
    private final UserRepository userRepository;
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

    private void checkWarehouseAccess(Warehouse warehouse, UserDetailsImpl actor, String operation) {
        if (actor == null || actor.getAuthorities() == null || warehouse == null) {
            return;
        }
        Set<String> roles = actor.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        if (roles.contains("ROLE_ADMIN")) {
            return;
        }

        boolean isManager = warehouse.getManager() != null && Objects.equals(warehouse.getManager().getId(), actor.getId());
        boolean assigned = isManager || userRepository.findById(actor.getId())
                .map(u -> u.getWarehouses() != null && u.getWarehouses().stream().anyMatch(w -> Objects.equals(w.getId(), warehouse.getId())))
                .orElse(false);

        if (!assigned) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "WAREHOUSE_NOT_ASSIGNED",
                    "Bạn không được phân công phụ trách kho " + warehouse.getName() + " (" + warehouse.getCode() + ") để thực hiện " + operation + ".");
        }
    }

    public boolean canViewCostPrice(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return false;
        }
        Set<String> roles = actor.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        return roles.contains("ROLE_ADMIN")
                || roles.contains("ROLE_WH_MANAGER")
                || roles.contains("ROLE_ACCOUNTANT")
                || roles.contains("ROLE_SALES_MANAGER");
    }

    /**
     * Tiêu chí 1 & 2: Lập phiếu điều chỉnh tồn kho.
     * - Dùng cho hàng hỏng, hàng mất, hàng hết hạn phải huỷ.
     * - Bắt buộc chọn lý do và tính rõ giá trị tổn thất (dựa trên giá vốn).
     * - Trạng thái ban đầu: PENDING_APPROVAL. Chưa thay đổi tồn kho thực tế.
     */
    @Transactional
    public StockAdjustmentResponse createAdjustment(StockAdjustmentCreateRequest req, UserDetailsImpl actor) {
        if (!StringUtils.hasText(req.getAdjustmentReason()) || !REASON_LABELS.containsKey(req.getAdjustmentReason().trim())) {
            throw BusinessException.badRequest("Bắt buộc phải chọn lý do điều chỉnh tồn kho hợp lệ (DAMAGED_GOODS, LOST_GOODS, EXPIRED_DISPOSAL, OTHER).");
        }

        LocalDate today = LocalDate.now(clock);
        if (req.getAdjustmentDate() != null && req.getAdjustmentDate().isAfter(today)) {
            throw BusinessException.badRequest("Ngày điều chỉnh không được ở tương lai (" + req.getAdjustmentDate() + ").");
        }

        Warehouse warehouse = warehouseRepository.findById(req.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng ID: " + req.getWarehouseId()));

        if (!"ACTIVE".equalsIgnoreCase(warehouse.getStatus())) {
            throw BusinessException.badRequest("Kho hàng " + warehouse.getName() + " đang ngừng hoạt động.");
        }

        // TC803-07: NV kho lập phiếu cho kho không được gán -> Bị chặn với HTTP 403 Forbidden
        checkWarehouseAccess(warehouse, actor, "lập phiếu điều chỉnh tồn kho");

        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw BusinessException.badRequest("Phiếu điều chỉnh phải có ít nhất một dòng mặt hàng.");
        }

        LocalDate adjDate = req.getAdjustmentDate() != null ? req.getAdjustmentDate() : today;
        String code = generateAdjustmentCode(adjDate);

        StockAdjustment adjustment = StockAdjustment.builder()
                .code(code)
                .adjustmentDate(adjDate)
                .warehouse(warehouse)
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .adjustmentReason(req.getAdjustmentReason().trim())
                .reasonDescription(req.getReasonDescription())
                .status(StockAdjustment.STATUS_PENDING_APPROVAL)
                .notes(req.getNotes())
                .createdById(actor != null ? actor.getId() : null)
                .createdByName(actor != null ? actor.getUsername() : "system")
                .build();

        // TC803-29: Tổng hợp số lượng yêu cầu theo sản phẩm và lô để chặn gửi nhiều dòng vượt tồn
        Map<Long, BigDecimal> totalAdjByProduct = new HashMap<>();
        Map<String, BigDecimal> totalAdjByProductLot = new HashMap<>();
        for (StockAdjustmentItemRequest itemReq : req.getItems()) {
            if (itemReq.getProductId() != null && itemReq.getAdjustmentQuantity() != null) {
                totalAdjByProduct.merge(itemReq.getProductId(), itemReq.getAdjustmentQuantity(), BigDecimal::add);
                if (StringUtils.hasText(itemReq.getBatchNumber())) {
                    String lotKey = itemReq.getProductId() + ":" + itemReq.getBatchNumber().trim();
                    totalAdjByProductLot.merge(lotKey, itemReq.getAdjustmentQuantity(), BigDecimal::add);
                }
            }
        }

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

            // TC803-13: SKU chưa có giá vốn (giá vốn = 0đ) -> Bắt buộc từ chối HTTP 400
            BigDecimal costPrice = product.getCostPrice();
            if (costPrice == null || costPrice.compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.badRequest(String.format(
                        "Sản phẩm %s (%s) chưa có giá vốn hợp lệ (giá vốn = 0đ). Bắt buộc phải có giá vốn để xác định giá trị tổn thất khi lập phiếu điều chỉnh.",
                        product.getSku(), product.getName()));
            }

            // Kiểm tra tồn kho hiện tại
            Inventory inventory = inventoryRepository.findByWarehouse_IdAndProduct_Id(warehouse.getId(), product.getId())
                    .orElse(null);
            BigDecimal currentStock = inventory != null && inventory.getPhysicalStock() != null
                    ? inventory.getPhysicalStock()
                    : BigDecimal.ZERO;
            BigDecimal availableStock = inventory != null ? inventory.getAvailableStock() : BigDecimal.ZERO;

            // Kiểm tra vượt tồn kho thực tế (đơn dòng & tổng các dòng)
            BigDecimal totalProdAdj = totalAdjByProduct.getOrDefault(product.getId(), adjQty);
            if (totalProdAdj.compareTo(currentStock) > 0) {
                throw BusinessException.badRequest(String.format(
                        "Số lượng điều chỉnh giảm (%s) vượt quá tồn kho thực tế hiện tại (%s) của sản phẩm %s tại kho %s.",
                        totalProdAdj.stripTrailingZeros().toPlainString(),
                        currentStock.stripTrailingZeros().toPlainString(),
                        product.getSku(),
                        warehouse.getName()
                ));
            }

            // TC803-18: Giảm vào phần hàng đang giữ chỗ (Reserved) -> Chặn HTTP 400 vì vượt tồn khả dụng
            if (totalProdAdj.compareTo(availableStock) > 0) {
                throw BusinessException.badRequest(String.format(
                        "Số lượng điều chỉnh giảm (%s) vượt quá tồn khả dụng hiện tại (%s) của sản phẩm %s tại kho %s (Tồn thực tế: %s, Giữ chỗ: %s).",
                        totalProdAdj.stripTrailingZeros().toPlainString(),
                        availableStock.stripTrailingZeros().toPlainString(),
                        product.getSku(),
                        warehouse.getName(),
                        currentStock.stripTrailingZeros().toPlainString(),
                        (inventory != null && inventory.getReservedStock() != null ? inventory.getReservedStock() : BigDecimal.ZERO).stripTrailingZeros().toPlainString()
                ));
            }

            // Kiểm tra lô nếu sản phẩm quản lý theo lô
            boolean hasBatch = Boolean.TRUE.equals(product.getHasBatchManagement());
            Long productLotId = null;
            if (hasBatch) {
                if (!StringUtils.hasText(itemReq.getBatchNumber())) {
                    throw BusinessException.badRequest("Mặt hàng " + product.getSku() + " quản lý theo lô, bắt buộc phải chọn số lô sản xuất.");
                }
                String cleanBatch = itemReq.getBatchNumber().trim();
                ProductLot lot = productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(
                        product.getId(), warehouse.getId(), cleanBatch).orElse(null);

                // TC803-15: Nhập số lô không tồn tại -> Bị chặn với HTTP 400 Bad Request
                if (lot == null) {
                    throw BusinessException.badRequest(String.format(
                            "Số lô %s của sản phẩm %s không tồn tại trong kho %s.",
                            cleanBatch, product.getSku(), warehouse.getName()));
                }

                // TC803-16: Giảm nhiều hơn tồn của lô -> Bị chặn với HTTP 400 Bad Request
                BigDecimal lotQty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
                String lotKey = product.getId() + ":" + cleanBatch;
                BigDecimal totalLotAdj = totalAdjByProductLot.getOrDefault(lotKey, adjQty);
                if (totalLotAdj.compareTo(lotQty) > 0) {
                    throw BusinessException.badRequest(String.format(
                            "Số lượng điều chỉnh giảm (%s) vượt quá tồn kho của lô %s (%s) tại kho %s.",
                            totalLotAdj.stripTrailingZeros().toPlainString(),
                            cleanBatch,
                            lotQty.stripTrailingZeros().toPlainString(),
                            warehouse.getName()));
                }
                productLotId = lot.getId();
            }

            // Tính giá trị tổn thất = số lượng giảm * giá vốn (costPrice)
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

        return toResponse(saved, canViewCostPrice(actor));
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
        // TC803-06: QL kho KHÔNG phụ trách kho duyệt phiếu -> HTTP 403 Forbidden
        checkWarehouseAccess(warehouse, actor, "phê duyệt phiếu điều chỉnh tồn kho");

        LocalDateTime now = LocalDateTime.now(clock);

        log.info("S8-03: Quản lý kho (ID: {}, User: {}) bắt đầu phê duyệt phiếu điều chỉnh {}: kho={}, số dòng={}, tổng tổn thất={}",
                actor.getId(), actor.getUsername(), adjustment.getCode(), warehouse.getCode(), adjustment.getLines().size(), adjustment.getTotalLossValue());

        Map<String, Object> beforeStocks = new LinkedHashMap<>();
        Map<String, Object> afterStocks = new LinkedHashMap<>();

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

            BigDecimal availableStock = inventory.getAvailableStock();
            if (availableStock.compareTo(adjQty) < 0) {
                throw BusinessException.badRequest(String.format(
                        "Tồn khả dụng của sản phẩm %s tại kho %s (%s) không đủ để điều chỉnh giảm (%s) vì vướng hàng đang giữ chỗ.",
                        product.getSku(), warehouse.getName(), availableStock, adjQty
                ));
            }

            BigDecimal newPhysical = oldPhysical.subtract(adjQty);
            inventory.setPhysicalStock(newPhysical);
            inventoryRepository.save(inventory);

            beforeStocks.put(product.getSku(), oldPhysical);
            afterStocks.put(product.getSku(), newPhysical);

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

        // TC803-20: Nhật ký duyệt ghi nhận tồn trước / sau (oldValue & newValue)
        try {
            String reasonLabel = REASON_LABELS.getOrDefault(saved.getAdjustmentReason(), saved.getAdjustmentReason());
            String oldValueStr = beforeStocks.toString();
            String newValueStr = afterStocks.toString();
            CreateAuditLogEntry auditEntry = CreateAuditLogEntry.builder()
                    .module(AuditModule.INVENTORY)
                    .action("APPROVE_STOCK_ADJUSTMENT")
                    .actorId(actor.getId()) // Bắt buộc có ID QL
                    .actorUsername(actor.getUsername())
                    .targetType("STOCK_ADJUSTMENT")
                    .targetId(saved.getId())
                    .targetCode(saved.getCode())
                    .oldValue(oldValueStr)
                    .newValue(newValueStr)
                    .reason(String.format("Quản lý kho duyệt phiếu điều chỉnh tồn kho %s: kho=%s, lý do=%s, tổng tổn thất=%s, tồn trước: %s, tồn sau: %s",
                            saved.getCode(), warehouse.getCode(), reasonLabel, formatVnd(saved.getTotalLossValue()), oldValueStr, newValueStr))
                    .build();
            auditLogService.record(auditEntry);
            log.info("S8-03: Đã ghi AuditLog ngầm duyệt điều chỉnh tồn kho thành công với ID QL={}", actor.getId());
        } catch (Exception ex) {
            log.warn("S8-03: Không thể ghi AuditLog ngầm: {}", ex.getMessage());
        }

        return toResponse(saved, canViewCostPrice(actor));
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

        // TC803-30: Từ chối duyệt phiếu nhưng không nhập lý do -> Bị chặn với HTTP 400 Bad Request
        String rejectReason = decision != null ? decision.getNotes() : null;
        if (!StringUtils.hasText(rejectReason)) {
            throw BusinessException.badRequest("Bắt buộc phải nhập lý do từ chối duyệt phiếu điều chỉnh tồn kho.");
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

        // TC803-31: QL kho từ chối phiếu ở kho không được gán -> Bị từ chối với HTTP 403 Forbidden
        checkWarehouseAccess(adjustment.getWarehouse(), actor, "từ chối phiếu điều chỉnh tồn kho");

        adjustment.setStatus(StockAdjustment.STATUS_REJECTED);
        adjustment.setRejectionReason(rejectReason.trim());
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

        return toResponse(saved, canViewCostPrice(actor));
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

        // TC803-09: Huỷ phiếu của người khác -> Bị chặn với HTTP 403 Forbidden (Chỉ người lập hoặc Quản lý kho mới được huỷ)
        if (actor != null) {
            Set<String> roles = actor.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            boolean isAdmin = roles.contains("ROLE_ADMIN");
            boolean isCreator = actor.getId().equals(adjustment.getCreatedById());
            boolean isWhManager = roles.contains("ROLE_WH_MANAGER");

            if (!isAdmin && !isCreator) {
                if (!isWhManager) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN_CANCEL",
                            "Chỉ người lập phiếu hoặc Quản lý kho mới có quyền huỷ phiếu điều chỉnh này.");
                }
                checkWarehouseAccess(adjustment.getWarehouse(), actor, "huỷ phiếu điều chỉnh tồn kho");
            }
        }

        adjustment.setStatus(StockAdjustment.STATUS_CANCELLED);
        if (StringUtils.hasText(cancelReason)) {
            adjustment.setNotes(adjustment.getNotes() != null
                    ? adjustment.getNotes() + " | Lý do huỷ: " + cancelReason
                    : "Lý do huỷ: " + cancelReason);
        }

        StockAdjustment saved = stockAdjustmentRepository.save(adjustment);
        return toResponse(saved, canViewCostPrice(actor));
    }

    @Transactional(readOnly = true)
    public StockAdjustmentResponse getAdjustmentDetail(Long id) {
        return getAdjustmentDetail(id, null);
    }

    @Transactional(readOnly = true)
    public StockAdjustmentResponse getAdjustmentDetail(Long id, UserDetailsImpl actor) {
        StockAdjustment adjustment = stockAdjustmentRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy phiếu điều chỉnh tồn kho ID: " + id));
        // TC803-10: NV kho xem giá vốn sản phẩm -> Ẩn giá vốn hoặc bị chặn
        boolean canViewCost = canViewCostPrice(actor);
        return toResponse(adjustment, canViewCost);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentResponse> searchAdjustments(
            Long warehouseId, String status, String reason,
            LocalDate fromDate, LocalDate toDate, String keyword,
            int page, int size) {
        return searchAdjustments(warehouseId, status, reason, fromDate, toDate, keyword, page, size, null);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockAdjustmentResponse> searchAdjustments(
            Long warehouseId, String status, String reason,
            LocalDate fromDate, LocalDate toDate, String keyword,
            int page, int size, UserDetailsImpl actor) {
        int p = Math.max(0, page);
        int s = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(p, s, Sort.by(Sort.Direction.DESC, "createdAt"));

        Set<Long> allowedWarehouseIds = null;
        if (actor != null && actor.getAuthorities() != null) {
            Set<String> roles = actor.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toSet());
            boolean broad = roles.contains("ROLE_ADMIN")
                    || roles.contains("ROLE_ACCOUNTANT")
                    || roles.contains("ROLE_SALES_MANAGER");
            if (!broad && (roles.contains("ROLE_WAREHOUSE") || roles.contains("ROLE_WH_MANAGER"))) {
                allowedWarehouseIds = userRepository.findById(actor.getId())
                        .map(u -> u.getWarehouses().stream().map(Warehouse::getId).collect(Collectors.toSet()))
                        .orElse(Set.of());
                if (warehouseId != null && !allowedWarehouseIds.contains(warehouseId)) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                            "Bạn không có quyền truy cập dữ liệu điều chỉnh của kho này.");
                }
            }
        }

        final Set<Long> finalAllowed = allowedWarehouseIds;
        Specification<StockAdjustment> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (warehouseId != null) {
                ps.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            } else if (finalAllowed != null) {
                if (finalAllowed.isEmpty()) {
                    ps.add(cb.disjunction());
                } else {
                    ps.add(root.get("warehouse").get("id").in(finalAllowed));
                }
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

        boolean canViewCost = canViewCostPrice(actor);
        List<StockAdjustmentResponse> content = pageResult.getContent().stream()
                .map(a -> toResponse(a, canViewCost))
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
        return toResponse(a, true);
    }

    private StockAdjustmentResponse toResponse(StockAdjustment a, boolean canViewCostPrice) {
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
                        .costPrice(canViewCostPrice ? l.getCostPrice() : null)
                        .lossValue(canViewCostPrice ? l.getLossValue() : null)
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
                .totalLossValue(canViewCostPrice ? a.getTotalLossValue() : null)
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
