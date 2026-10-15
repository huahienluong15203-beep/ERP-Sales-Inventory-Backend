package com.erp.backend.service;

import com.erp.backend.dto.inventory.lot.*;
import com.erp.backend.dto.product.ProductResponse;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service xử lý nghiệp vụ quản lý Lô hàng & Hạn sử dụng (SCRUM-48 / S6-01).
 * Tuân thủ quy chuẩn kiến trúc enterprise, không hardcode mã nhân viên,
 * hỗ trợ FEFO, cảnh báo sắp hết hạn và cơ chế xác nhận ghi đè xuất kho quá hạn.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductLotService {

    private final ProductLotRepository productLotRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final SupplierRepository supplierRepository;
    private final AuditLogService auditLogService;
    private final ProductService productService;

    // =========================================================================
    // 1. TÌM KIẾM, LỌC & CHI TIẾT LÔ HÀNG (S6-01)
    // =========================================================================

    /**
     * Tra cứu danh sách lô hàng có phân trang và bộ lọc linh hoạt.
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductLotResponse> searchLots(
            Long warehouseId,
            Long productId,
            String keyword,
            String status,
            String expiryStatus,
            Integer nearExpiryDays,
            Pageable pageable,
            UserDetailsImpl actor) {

        assertWarehouseAccess(warehouseId, actor);
        Set<Long> allowedWarehouses = allowedWarehouseIds(actor);

        int threshold = (nearExpiryDays != null && nearExpiryDays > 0) ? nearExpiryDays : 30;

        Specification<ProductLot> spec = ProductLotSpecifications.filter(
                allowedWarehouses,
                warehouseId,
                productId,
                keyword,
                status,
                expiryStatus,
                threshold
        );

        // Giới hạn max size theo quy chuẩn backend enterprise
        int pageSize = Math.min(pageable.getPageSize(), 100);
        Pageable safePageable = PageRequest.of(pageable.getPageNumber(), pageSize, pageable.getSort());

        Page<ProductLot> pageResult = productLotRepository.findAll(spec, safePageable);
        List<ProductLotResponse> content = pageResult.getContent().stream()
                .map(lot -> mapToResponse(lot, threshold))
                .collect(Collectors.toList());

        return new PageResponse<>(content, pageResult.getNumber(), pageResult.getSize(),
                pageResult.getTotalElements(), pageResult.getTotalPages());
    }

    /**
     * Xem thông tin chi tiết một lô hàng theo ID.
     */
    @Transactional(readOnly = true)
    public ProductLotResponse getLotById(Long id, UserDetailsImpl actor) {
        ProductLot lot = productLotRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy lô hàng với ID: " + id));

        assertWarehouseAccess(lot.getWarehouse().getId(), actor);
        return mapToResponse(lot, 30);
    }

    // =========================================================================
    // 2. KHAI BÁO & CẬP NHẬT LÔ HÀNG (S6-01)
    // =========================================================================

    /**
     * Khai báo một lô hàng mới cho SKU tại kho.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductLotResponse createLot(ProductLotCreateRequest request, UserDetailsImpl actor) {
        assertWarehouseAccess(request.getWarehouseId(), actor);

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID: " + request.getProductId()));

        Warehouse warehouse = warehouseRepository.findById(request.getWarehouseId())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho với ID: " + request.getWarehouseId()));

        Supplier supplier = null;
        if (request.getSupplierId() != null) {
            supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy nhà cung cấp với ID: " + request.getSupplierId()));
        }

        // Kiểm tra tính hợp lệ của ngày sản xuất và hạn sử dụng
        if (request.getManufactureDate() != null && request.getExpiredDate() != null
                && request.getExpiredDate().isBefore(request.getManufactureDate())) {
            throw BusinessException.badRequest("INVALID_EXPIRY_DATE", "Hạn sử dụng không được nhỏ hơn ngày sản xuất");
        }

        // Kiểm tra trùng số lô cho cùng sản phẩm tại cùng một kho
        String cleanBatchNumber = request.getBatchNumber().trim();
        if (productLotRepository.existsByProduct_IdAndWarehouse_IdAndBatchNumber(product.getId(), warehouse.getId(), cleanBatchNumber)) {
            throw BusinessException.conflict("DUPLICATE_BATCH_NUMBER",
                    "Số lô '" + cleanBatchNumber + "' đã tồn tại cho sản phẩm này tại kho " + warehouse.getName(), "batchNumber");
        }

        // Tự động bật quản lý lô cho sản phẩm nếu sản phẩm chưa được bật
        if (!Boolean.TRUE.equals(product.getHasBatchManagement())) {
            product.setHasBatchManagement(true);
            productRepository.save(product);
            log.info("Automatically enabled batch management for product SKU={}", product.getSku());
        }

        BigDecimal qty = request.getInitialQuantity() != null ? request.getInitialQuantity() : BigDecimal.ZERO;

        ProductLot lot = ProductLot.builder()
                .product(product)
                .warehouse(warehouse)
                .batchNumber(cleanBatchNumber)
                .manufactureDate(request.getManufactureDate())
                .expiredDate(request.getExpiredDate())
                .quantity(qty)
                .supplier(supplier)
                .notes(request.getNotes())
                .status("ACTIVE")
                .build();

        ProductLot saved = productLotRepository.save(lot);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.INVENTORY, "CREATE_LOT", "PRODUCT_LOT", saved.getId(),
                    saved.getBatchNumber(), null, "Created lot " + saved.getBatchNumber(),
                    "Khai báo lô hàng mới", actor);
        }

        return mapToResponse(saved, 30);
    }

    /**
     * Cập nhật thông tin lô hàng (NSX, HSD, ghi chú, trạng thái ACTIVE/LOCKED).
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductLotResponse updateLot(Long id, ProductLotUpdateRequest request, UserDetailsImpl actor) {
        ProductLot lot = productLotRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy lô hàng với ID: " + id));

        assertWarehouseAccess(lot.getWarehouse().getId(), actor);

        LocalDate newMfd = request.getManufactureDate() != null ? request.getManufactureDate() : lot.getManufactureDate();
        LocalDate newExp = request.getExpiredDate() != null ? request.getExpiredDate() : lot.getExpiredDate();

        if (newMfd != null && newExp != null && newExp.isBefore(newMfd)) {
            throw BusinessException.badRequest("INVALID_EXPIRY_DATE", "Hạn sử dụng không được nhỏ hơn ngày sản xuất");
        }

        if (request.getManufactureDate() != null) {
            lot.setManufactureDate(request.getManufactureDate());
        }
        if (request.getExpiredDate() != null) {
            lot.setExpiredDate(request.getExpiredDate());
        }
        if (request.getNotes() != null) {
            lot.setNotes(request.getNotes());
        }
        if (StringUtils.hasText(request.getStatus())) {
            String upperStatus = request.getStatus().trim().toUpperCase();
            if (!"ACTIVE".equals(upperStatus) && !"LOCKED".equals(upperStatus)) {
                throw BusinessException.badRequest("INVALID_STATUS", "Trạng thái lô chỉ có thể là ACTIVE hoặc LOCKED");
            }
            lot.setStatus(upperStatus);
        }

        ProductLot saved = productLotRepository.save(lot);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.INVENTORY, "UPDATE_LOT", "PRODUCT_LOT", saved.getId(),
                    saved.getBatchNumber(), null, "Updated lot " + saved.getBatchNumber(),
                    "Cập nhật thông tin lô hàng", actor);
        }

        return mapToResponse(saved, 30);
    }

    // =========================================================================
    // 3. BẬT / TẮT QUẢN LÝ LÔ THEO TỪNG SKU (S6-01)
    // =========================================================================

    /**
     * Bật hoặc tắt cờ quản lý lô (hasBatchManagement) theo từng SKU.
     * Chỉ Quản lý kho, Quản lý bán hàng hoặc Quản trị viên mới có quyền thay đổi.
     */
    @Transactional(rollbackFor = Exception.class)
    public ProductResponse toggleBatchManagement(Long productId, boolean enabled, UserDetailsImpl actor) {
        if (!hasAnyRole(actor, "WH_MANAGER", "ADMIN", "SALES_MANAGER")) {
            throw BusinessException.forbidden("ACCESS_DENIED", "Chỉ Quản lý kho, Quản lý bán hàng hoặc Quản trị viên mới có quyền bật/tắt quản lý lô");
        }

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID: " + productId));

        boolean oldValue = Boolean.TRUE.equals(product.getHasBatchManagement());
        product.setHasBatchManagement(enabled);
        Product saved = productRepository.save(product);

        if (auditLogService != null) {
            auditLogService.record(AuditModule.PRODUCT, "TOGGLE_BATCH_MANAGEMENT", "PRODUCT", saved.getId(),
                    saved.getSku(), String.valueOf(oldValue), String.valueOf(enabled),
                    (enabled ? "Bật" : "Tắt") + " quản lý lô theo SKU", actor);
        }

        return productService.getById(saved.getId(), actor);
    }

    // =========================================================================
    // 4. TÍNH TỒN KHO THEO LÔ (S6-01)
    // =========================================================================

    /**
     * Tính toán tổng tồn thực tế, tồn còn hạn, tồn sắp hết hạn, tồn đã quá hạn
     * và trả về danh sách chi tiết lô sắp xếp theo FEFO (hạn sử dụng gần nhất lên trước).
     */
    @Transactional(readOnly = true)
    public ProductLotStockResponse calculateStockByLot(Long productId, Long warehouseId, Integer nearExpiryDays, UserDetailsImpl actor) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm với ID: " + productId));

        Warehouse warehouse = null;
        if (warehouseId != null) {
            warehouse = warehouseRepository.findById(warehouseId)
                    .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho với ID: " + warehouseId));
            assertWarehouseAccess(warehouseId, actor);
        }

        int threshold = (nearExpiryDays != null && nearExpiryDays > 0) ? nearExpiryDays : 30;
        LocalDate today = LocalDate.now();
        LocalDate nearExpiryDate = today.plusDays(threshold);

        List<ProductLot> lots;
        if (warehouseId != null) {
            lots = productLotRepository.findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(productId, warehouseId);
        } else {
            Set<Long> allowedWarehouses = allowedWarehouseIds(actor);
            if (allowedWarehouses != null && allowedWarehouses.isEmpty()) {
                lots = Collections.emptyList();
            } else {
                lots = productLotRepository.findByProduct_Id(productId).stream()
                        .filter(l -> allowedWarehouses == null || allowedWarehouses.contains(l.getWarehouse().getId()))
                        .sorted(Comparator.comparing(ProductLot::getExpiredDate, Comparator.nullsLast(Comparator.naturalOrder())))
                        .collect(Collectors.toList());
            }
        }

        BigDecimal totalStock = BigDecimal.ZERO;
        BigDecimal validStock = BigDecimal.ZERO;
        BigDecimal nearExpiryStock = BigDecimal.ZERO;
        BigDecimal expiredStock = BigDecimal.ZERO;

        List<ProductLotResponse> lotResponses = new ArrayList<>();

        for (ProductLot lot : lots) {
            BigDecimal qty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
            totalStock = totalStock.add(qty);

            LocalDate expDate = lot.getExpiredDate();
            if (expDate != null) {
                if (expDate.isBefore(today)) {
                    expiredStock = expiredStock.add(qty);
                } else {
                    validStock = validStock.add(qty);
                    if (!expDate.isAfter(nearExpiryDate)) {
                        nearExpiryStock = nearExpiryStock.add(qty);
                    }
                }
            } else {
                // Không có HSD thì mặc định coi như còn hạn an toàn
                validStock = validStock.add(qty);
            }

            lotResponses.add(mapToResponse(lot, threshold));
        }

        return ProductLotStockResponse.builder()
                .productId(product.getId())
                .productSku(product.getSku())
                .productName(product.getName())
                .baseUnit(product.getBaseUnit())
                .hasBatchManagement(Boolean.TRUE.equals(product.getHasBatchManagement()))
                .warehouseId(warehouse != null ? warehouse.getId() : null)
                .warehouseCode(warehouse != null ? warehouse.getCode() : null)
                .warehouseName(warehouse != null ? warehouse.getName() : null)
                .totalStock(totalStock)
                .validStock(validStock)
                .nearExpiryStock(nearExpiryStock)
                .expiredStock(expiredStock)
                .lots(lotResponses)
                .build();
    }

    // =========================================================================
    // 5. HÀM KIỂM TRA HSD & XÁC THỰC XUẤT KHO THEO LÔ (S6-01)
    // =========================================================================

    /**
     * Hàm kiểm tra HSD và tính hợp lệ khi xuất kho:
     * - Không cho xuất lô bị khóa (status = LOCKED).
     * - Không cho xuất nếu số lượng tồn trong lô không đủ.
     * - Không cho xuất lô đã quá hạn (expiredDate < today), TRỪ KHI Quản lý kho hoặc Admin xác nhận ghi đè kèm lý do.
     * - Cảnh báo nếu lô sắp hết hạn trong ngưỡng 30 ngày.
     */
    @Transactional(readOnly = true)
    public LotDispatchValidationResponse validateLotForDispatch(LotDispatchValidationRequest request, UserDetailsImpl actor) {
        assertWarehouseAccess(request.getWarehouseId(), actor);

        ProductLot lot = productLotRepository.findByProduct_IdAndWarehouse_IdAndBatchNumber(
                        request.getProductId(), request.getWarehouseId(), request.getBatchNumber().trim())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy lô '" + request.getBatchNumber()
                        + "' của sản phẩm tại kho chỉ định"));

        LocalDate today = LocalDate.now();
        LocalDate expDate = lot.getExpiredDate();
        Long daysToExpiration = expDate != null ? ChronoUnit.DAYS.between(today, expDate) : null;
        BigDecimal availableQty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
        BigDecimal reqQty = request.getQuantity();

        boolean isLocked = "LOCKED".equalsIgnoreCase(lot.getStatus());
        boolean isExpired = expDate != null && expDate.isBefore(today);
        boolean isNearExpiry = expDate != null && !isExpired && !expDate.isAfter(today.plusDays(30));
        boolean isInsufficient = availableQty.compareTo(reqQty) < 0;

        // 1. Kiểm tra trạng thái khóa
        if (isLocked) {
            return LotDispatchValidationResponse.builder()
                    .valid(false)
                    .locked(true)
                    .expired(isExpired)
                    .nearExpiry(isNearExpiry)
                    .insufficientQuantity(isInsufficient)
                    .overrideApplied(false)
                    .message("Lô hàng '" + lot.getBatchNumber() + "' đang bị khóa (LOCKED), không được phép xuất kho.")
                    .batchNumber(lot.getBatchNumber())
                    .expiredDate(expDate)
                    .daysToExpiration(daysToExpiration)
                    .availableQuantity(availableQty)
                    .requestedQuantity(reqQty)
                    .build();
        }

        // 2. Kiểm tra tồn khả dụng trong lô
        if (isInsufficient) {
            return LotDispatchValidationResponse.builder()
                    .valid(false)
                    .locked(false)
                    .expired(isExpired)
                    .nearExpiry(isNearExpiry)
                    .insufficientQuantity(true)
                    .overrideApplied(false)
                    .message("Số lượng tồn trong lô '" + lot.getBatchNumber() + "' (" + availableQty
                            + ") không đủ để xuất (" + reqQty + ").")
                    .batchNumber(lot.getBatchNumber())
                    .expiredDate(expDate)
                    .daysToExpiration(daysToExpiration)
                    .availableQuantity(availableQty)
                    .requestedQuantity(reqQty)
                    .build();
        }

        // 3. Kiểm tra quá hạn sử dụng (S6-01: Không cho xuất lô đã quá hạn, trừ khi Quản lý kho xác nhận ghi đè kèm lý do)
        if (isExpired) {
            if (Boolean.TRUE.equals(request.getOverrideExpired())) {
                // Xác minh quyền của người thực hiện ghi đè: Chỉ WH_MANAGER hoặc ADMIN
                if (!hasAnyRole(actor, "WH_MANAGER", "ADMIN")) {
                    throw BusinessException.forbidden("OVERRIDE_NOT_ALLOWED",
                            "Chỉ Quản lý kho hoặc Quản trị viên mới có quyền xác nhận ghi đè cho phép xuất lô quá hạn");
                }

                if (!StringUtils.hasText(request.getOverrideReason()) || request.getOverrideReason().trim().length() < 5) {
                    throw BusinessException.badRequest("OVERRIDE_REASON_REQUIRED",
                            "Cần nhập lý do ghi đè xuất lô quá hạn (tối thiểu 5 ký tự)");
                }

                // Ghi nhận audit log cho thao tác ghi đè xuất lô quá hạn
                if (auditLogService != null) {
                    auditLogService.record(AuditModule.INVENTORY, "OVERRIDE_EXPIRED_DISPATCH", "PRODUCT_LOT", lot.getId(),
                            lot.getBatchNumber(), "EXPIRED", "OVERRIDE_ALLOWED",
                            "Ghi đè xuất kho lô quá hạn: " + request.getOverrideReason().trim(), actor);
                }

                return LotDispatchValidationResponse.builder()
                        .valid(true)
                        .locked(false)
                        .expired(true)
                        .nearExpiry(false)
                        .insufficientQuantity(false)
                        .overrideApplied(true)
                        .message("Quản lý kho đã xác nhận ghi đè cho phép xuất lô quá hạn. Lý do: " + request.getOverrideReason().trim())
                        .batchNumber(lot.getBatchNumber())
                        .expiredDate(expDate)
                        .daysToExpiration(daysToExpiration)
                        .availableQuantity(availableQty)
                        .requestedQuantity(reqQty)
                        .build();
            } else {
                return LotDispatchValidationResponse.builder()
                        .valid(false)
                        .locked(false)
                        .expired(true)
                        .nearExpiry(false)
                        .insufficientQuantity(false)
                        .overrideApplied(false)
                        .message("Lô hàng '" + lot.getBatchNumber() + "' đã hết hạn sử dụng vào ngày " + expDate
                                + " (quá hạn " + Math.abs(daysToExpiration) + " ngày). Nghiêm cấm xuất kho trừ khi Quản lý kho xác nhận ghi đè kèm lý do.")
                        .batchNumber(lot.getBatchNumber())
                        .expiredDate(expDate)
                        .daysToExpiration(daysToExpiration)
                        .availableQuantity(availableQty)
                        .requestedQuantity(reqQty)
                        .build();
            }
        }

        // 4. Lô an toàn hoặc sắp hết hạn
        String successMessage = isNearExpiry
                ? "Lô hàng sắp hết hạn (còn " + daysToExpiration + " ngày). Đủ điều kiện xuất kho theo quy tắc FEFO."
                : "Lô hàng đủ điều kiện xuất kho (còn " + daysToExpiration + " ngày đến hạn sử dụng).";

        return LotDispatchValidationResponse.builder()
                .valid(true)
                .locked(false)
                .expired(false)
                .nearExpiry(isNearExpiry)
                .insufficientQuantity(false)
                .overrideApplied(false)
                .message(successMessage)
                .batchNumber(lot.getBatchNumber())
                .expiredDate(expDate)
                .daysToExpiration(daysToExpiration)
                .availableQuantity(availableQty)
                .requestedQuantity(reqQty)
                .build();
    }

    // =========================================================================
    // 6. CẢNH BÁO LÔ SẮP HẾT HẠN & THỐNG KÊ TỔNG QUAN (S6-01)
    // =========================================================================

    /**
     * Lấy danh sách lô sắp hết hạn theo ngưỡng ngày khai báo được (mặc định 30 ngày).
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductLotResponse> getExpiringLots(Integer daysThreshold, Long warehouseId, Pageable pageable, UserDetailsImpl actor) {
        int threshold = (daysThreshold != null && daysThreshold > 0) ? daysThreshold : 30;
        return searchLots(warehouseId, null, null, "ACTIVE", "NEAR_EXPIRATION", threshold, pageable, actor);
    }

    /**
     * Thống kê tổng số lượng lô và tồn kho theo từng trạng thái hạn sử dụng.
     */
    @Transactional(readOnly = true)
    public LotStockSummaryResponse getLotSummary(Integer daysThreshold, Long warehouseId, UserDetailsImpl actor) {
        assertWarehouseAccess(warehouseId, actor);
        Set<Long> allowedWarehouses = allowedWarehouseIds(actor);

        int threshold = (daysThreshold != null && daysThreshold > 0) ? daysThreshold : 30;
        LocalDate today = LocalDate.now();
        LocalDate nearExpiryDate = today.plusDays(threshold);

        List<ProductLot> allLots;
        if (warehouseId != null) {
            allLots = productLotRepository.findByWarehouse_Id(warehouseId);
        } else if (allowedWarehouses != null) {
            if (allowedWarehouses.isEmpty()) {
                allLots = Collections.emptyList();
            } else {
                allLots = productLotRepository.findAll().stream()
                        .filter(l -> allowedWarehouses.contains(l.getWarehouse().getId()))
                        .collect(Collectors.toList());
            }
        } else {
            allLots = productLotRepository.findAll();
        }

        long totalLots = allLots.size();
        BigDecimal totalQuantity = BigDecimal.ZERO;

        long safeLots = 0;
        BigDecimal safeQuantity = BigDecimal.ZERO;

        long nearExpiryLots = 0;
        BigDecimal nearExpiryQuantity = BigDecimal.ZERO;

        long expiredLots = 0;
        BigDecimal expiredQuantity = BigDecimal.ZERO;

        long outOfStockLots = 0;
        BigDecimal outOfStockQuantity = BigDecimal.ZERO;

        for (ProductLot lot : allLots) {
            BigDecimal qty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
            totalQuantity = totalQuantity.add(qty);

            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                outOfStockLots++;
                outOfStockQuantity = outOfStockQuantity.add(qty);
                continue;
            }

            LocalDate exp = lot.getExpiredDate();
            if (exp == null) {
                safeLots++;
                safeQuantity = safeQuantity.add(qty);
            } else if (exp.isBefore(today)) {
                expiredLots++;
                expiredQuantity = expiredQuantity.add(qty);
            } else if (!exp.isAfter(nearExpiryDate)) {
                nearExpiryLots++;
                nearExpiryQuantity = nearExpiryQuantity.add(qty);
            } else {
                safeLots++;
                safeQuantity = safeQuantity.add(qty);
            }
        }

        return LotStockSummaryResponse.builder()
                .totalLots(totalLots)
                .totalQuantity(totalQuantity)
                .safeLots(safeLots)
                .safeQuantity(safeQuantity)
                .nearExpiryLots(nearExpiryLots)
                .nearExpiryQuantity(nearExpiryQuantity)
                .expiredLots(expiredLots)
                .expiredQuantity(expiredQuantity)
                .outOfStockLots(outOfStockLots)
                .outOfStockQuantity(outOfStockQuantity)
                .daysThresholdApplied(threshold)
                .build();
    }

    // =========================================================================
    // HELPER METHODS
    // =========================================================================

    public ProductLotResponse mapToResponse(ProductLot lot, Integer nearExpiryDays) {
        if (lot == null) return null;
        int threshold = (nearExpiryDays != null && nearExpiryDays > 0) ? nearExpiryDays : 30;
        LocalDate today = LocalDate.now();
        Long daysToExpiration = null;
        String expiryStatus = "UNKNOWN";

        if (lot.getExpiredDate() != null) {
            daysToExpiration = ChronoUnit.DAYS.between(today, lot.getExpiredDate());
            BigDecimal qty = lot.getQuantity() != null ? lot.getQuantity() : BigDecimal.ZERO;
            if (qty.compareTo(BigDecimal.ZERO) <= 0) {
                expiryStatus = "OUT_OF_STOCK";
            } else if (daysToExpiration < 0) {
                expiryStatus = "EXPIRED";
            } else if (daysToExpiration <= threshold) {
                expiryStatus = "NEAR_EXPIRATION";
            } else {
                expiryStatus = "SAFE";
            }
        } else if (lot.getQuantity() == null || lot.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            expiryStatus = "OUT_OF_STOCK";
        } else {
            expiryStatus = "SAFE";
        }

        Product p = lot.getProduct();
        Warehouse w = lot.getWarehouse();
        Supplier s = lot.getSupplier();

        return ProductLotResponse.builder()
                .id(lot.getId())
                .productId(p != null ? p.getId() : null)
                .productSku(p != null ? p.getSku() : null)
                .productName(p != null ? p.getName() : null)
                .productBaseUnit(p != null ? p.getBaseUnit() : null)
                .warehouseId(w != null ? w.getId() : null)
                .warehouseCode(w != null ? w.getCode() : null)
                .warehouseName(w != null ? w.getName() : null)
                .batchNumber(lot.getBatchNumber())
                .manufactureDate(lot.getManufactureDate())
                .expiredDate(lot.getExpiredDate())
                .quantity(lot.getQuantity())
                .supplierId(s != null ? s.getId() : null)
                .supplierCode(s != null ? s.getCode() : null)
                .supplierName(s != null ? s.getName() : null)
                .notes(lot.getNotes())
                .status(lot.getStatus())
                .daysToExpiration(daysToExpiration)
                .expiryStatus(expiryStatus)
                .createdAt(lot.getCreatedAt())
                .updatedAt(lot.getUpdatedAt())
                .build();
    }

    private void assertWarehouseAccess(Long warehouseId, UserDetailsImpl actor) {
        if (warehouseId == null) return;
        Set<Long> allowed = allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(warehouseId)) {
            throw BusinessException.forbidden("ACCESS_DENIED", "Bạn không có quyền thao tác trên kho này");
        }
    }

    Set<Long> allowedWarehouseIds(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        actor.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        boolean broad = roles.contains("ROLE_ADMIN")
                || roles.contains("ROLE_ACCOUNTANT") || roles.contains("ROLE_SALES_MANAGER");
        if (broad) {
            return null; // null = xem tất cả kho
        }
        if (roles.contains("ROLE_WAREHOUSE") || roles.contains("ROLE_WH_MANAGER")) {
            Set<Long> ids = userRepository.findById(actor.getId())
                    .map(u -> {
                        Set<Long> s = new HashSet<>();
                        u.getWarehouses().forEach(w -> s.add(w.getId()));
                        return s;
                    })
                    .orElse(Set.of());
            if (!ids.isEmpty()) {
                return ids;
            }
            Set<Long> fallback = new HashSet<>();
            warehouseRepository.findByCodeIgnoreCase("WH-MB01")
                    .ifPresent(wh -> fallback.add(wh.getId()));
            return fallback.isEmpty() ? null : fallback;
        }
        return null;
    }

    public static boolean hasAnyRole(UserDetailsImpl actor, String... roles) {
        if (actor == null || actor.getAuthorities() == null) return false;
        Set<String> userRoles = actor.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
        for (String r : roles) {
            String roleWithPrefix = r.startsWith("ROLE_") ? r : "ROLE_" + r;
            if (userRoles.contains(roleWithPrefix)) {
                return true;
            }
        }
        return false;
    }
}
