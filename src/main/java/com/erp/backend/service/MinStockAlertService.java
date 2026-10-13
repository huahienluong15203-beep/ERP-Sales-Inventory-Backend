package com.erp.backend.service;

import com.erp.backend.dto.inventory.alert.MinStockAlertResponse;
import com.erp.backend.dto.inventory.alert.MinStockSummaryResponse;
import com.erp.backend.dto.inventory.alert.UpdateMinThresholdPayload;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * S5-09: Service Quản lý cấu hình ngưỡng tồn tối thiểu & Cảnh báo đứt hàng.
 * - AC1: Khai báo tồn tối thiểu theo SKU và theo kho.
 * - AC2: Hàng dưới ngưỡng hiển thị nổi bật trong sổ tồn và trên dashboard kho.
 * - Subtask SCRUM-164: API check SKU dưới định mức.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinStockAlertService {

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MinStockThresholdRepository minStockThresholdRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductLotRepository productLotRepository;
    private final UserRepository userRepository;

    /**
     * Lấy danh sách cấu hình định mức tồn tối thiểu & trạng thái cảnh báo theo kho (AC1 & AC2).
     */
    @Transactional(readOnly = true)
    public List<MinStockAlertResponse> fetchMinStockConfigs(String warehouseCode,
                                                            Boolean belowThresholdOnly,
                                                            String keyword,
                                                            UserDetailsImpl actor) {
        return fetchMinStockConfigs(warehouseCode, belowThresholdOnly, null, keyword, actor);
    }

    @Transactional(readOnly = true)
    public List<MinStockAlertResponse> fetchMinStockConfigs(String warehouseCode,
                                                            Boolean belowThresholdOnly,
                                                            String status,
                                                            String keyword,
                                                            UserDetailsImpl actor) {
        return fetchMinStockConfigs(warehouseCode, null, belowThresholdOnly, status, keyword, actor);
    }

    @Transactional(readOnly = true)
    public List<MinStockAlertResponse> fetchMinStockConfigs(String warehouseCode,
                                                            String category,
                                                            Boolean belowThresholdOnly,
                                                            String status,
                                                            String keyword,
                                                            UserDetailsImpl actor) {
        Set<Long> allowedWhIds = allowedWarehouseIds(actor);

        List<Warehouse> targetWarehouses;
        if (StringUtils.hasText(warehouseCode) && !"ALL".equalsIgnoreCase(warehouseCode.trim())) {
            Warehouse wh = warehouseRepository.findByCodeIgnoreCase(warehouseCode.trim())
                    .orElse(null);
            if (wh == null) {
                return Collections.emptyList();
            }
            if (allowedWhIds != null && !allowedWhIds.contains(wh.getId())) {
                return Collections.emptyList();
            }
            targetWarehouses = List.of(wh);
        } else {
            targetWarehouses = warehouseRepository.findAll().stream()
                    .filter(w -> allowedWhIds == null || allowedWhIds.contains(w.getId()))
                    .filter(w -> !"INACTIVE".equalsIgnoreCase(w.getStatus()))
                    .sorted(Comparator.comparing(Warehouse::getCode))
                    .toList();
        }

        if (targetWarehouses.isEmpty()) {
            return Collections.emptyList();
        }

        String kw = StringUtils.hasText(keyword) ? keyword.trim().toLowerCase() : null;
        String cat = StringUtils.hasText(category) && !"ALL".equalsIgnoreCase(category.trim())
                ? category.trim().toLowerCase()
                : null;
        boolean filterBelowOnly = Boolean.TRUE.equals(belowThresholdOnly);

        // Lấy toàn bộ sản phẩm đang hoạt động (lọc theo nhóm hàng & từ khóa ngay nếu có để tối ưu tốc độ)
        List<Product> products = productRepository.findAll().stream()
                .filter(p -> !"INACTIVE".equalsIgnoreCase(p.getStatus()))
                .filter(p -> cat == null || (p.getCategory() != null && p.getCategory().toLowerCase().contains(cat)))
                .filter(p -> kw == null || (
                        (p.getSku() != null && p.getSku().toLowerCase().contains(kw)) ||
                        (p.getName() != null && p.getName().toLowerCase().contains(kw)) ||
                        (p.getCategory() != null && p.getCategory().toLowerCase().contains(kw))
                ))
                .toList();

        List<MinStockAlertResponse> result = new ArrayList<>();

        for (Warehouse wh : targetWarehouses) {
            // Map tồn kho hiện tại theo sản phẩm
            Map<Long, Inventory> invMap = inventoryRepository.findByWarehouse_Id(wh.getId()).stream()
                    .collect(Collectors.toMap(i -> i.getProduct().getId(), Function.identity(), (a, b) -> a));

            // Map ngưỡng tối thiểu đã khai báo theo sản phẩm
            Map<Long, MinStockThreshold> threshMap = minStockThresholdRepository.findByWarehouse_Id(wh.getId()).stream()
                    .collect(Collectors.toMap(t -> t.getProduct().getId(), Function.identity(), (a, b) -> a));

            // Map lô hàng gần nhất để lấy thông tin NCC / ngày nhập
            Map<Long, ProductLot> lotMap = productLotRepository.findByWarehouse_Id(wh.getId()).stream()
                    .filter(lot -> lot.getProduct() != null)
                    .collect(Collectors.toMap(
                            lot -> lot.getProduct().getId(),
                            Function.identity(),
                            (a, b) -> (a.getCreatedAt() != null && b.getCreatedAt() != null && a.getCreatedAt().isAfter(b.getCreatedAt())) ? a : b
                    ));

            for (Product p : products) {

                Inventory inv = invMap.get(p.getId());
                MinStockThreshold thresh = threshMap.get(p.getId());
                ProductLot lot = lotMap.get(p.getId());

                BigDecimal minThreshold = thresh != null && thresh.getMinThreshold() != null
                        ? thresh.getMinThreshold()
                        : BigDecimal.ZERO;

                BigDecimal physicalStock = inv != null && inv.getPhysicalStock() != null
                        ? inv.getPhysicalStock()
                        : BigDecimal.ZERO;

                BigDecimal reservedStock = inv != null && inv.getReservedStock() != null
                        ? inv.getReservedStock()
                        : BigDecimal.ZERO;

                BigDecimal availableStock = physicalStock.subtract(reservedStock);

                // Kiểm tra dưới định mức (AC2)
                boolean isBelow = minThreshold.compareTo(BigDecimal.ZERO) > 0
                        && physicalStock.compareTo(minThreshold) < 0;

                BigDecimal deficit = isBelow
                        ? minThreshold.subtract(physicalStock).max(BigDecimal.ZERO)
                        : BigDecimal.ZERO;

                // Phân loại mức độ cảnh báo (CRITICAL, WARNING, SAFE)
                String severity;
                if (!isBelow) {
                    severity = "SAFE";
                } else if (physicalStock.compareTo(BigDecimal.ZERO) <= 0
                        || physicalStock.compareTo(minThreshold.multiply(new BigDecimal("0.5"))) < 0) {
                    severity = "CRITICAL"; // Tồn = 0 hoặc dưới 50% định mức: Báo đỏ nguy cấp
                } else {
                    severity = "WARNING";  // Dưới định mức nhưng >= 50%
                }

                // Lọc theo trạng thái tồn (ALL, BELOW, CRITICAL, SAFE)
                if (status != null && !status.trim().isEmpty() && !"ALL".equalsIgnoreCase(status.trim())) {
                    if ("BELOW".equalsIgnoreCase(status.trim())) {
                        if (!isBelow) continue;
                    } else if ("CRITICAL".equalsIgnoreCase(status.trim())) {
                        if (!"CRITICAL".equalsIgnoreCase(severity)) continue;
                    } else if ("SAFE".equalsIgnoreCase(status.trim())) {
                        if (isBelow) continue;
                    }
                } else if (filterBelowOnly && !isBelow) {
                    continue;
                }

                // Đề xuất lượng nhập thêm: thiếu hụt + 50% ngưỡng an toàn
                BigDecimal suggestedReorder = isBelow
                        ? deficit.add(minThreshold.multiply(new BigDecimal("0.5"))).setScale(0, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;

                String supplierCode = null;
                String supplierName = null;
                String lastRestocked = null;
                if (lot != null && lot.getSupplier() != null) {
                    supplierCode = lot.getSupplier().getCode();
                    supplierName = lot.getSupplier().getName();
                }
                if (lot != null && lot.getCreatedAt() != null) {
                    lastRestocked = lot.getCreatedAt().toLocalDate().toString();
                } else if (inv != null && inv.getUpdatedAt() != null) {
                    lastRestocked = inv.getUpdatedAt().toLocalDate().toString();
                }

                String updatedIso = thresh != null && thresh.getUpdatedAt() != null
                        ? thresh.getUpdatedAt().format(ISO_FORMATTER)
                        : (inv != null && inv.getUpdatedAt() != null ? inv.getUpdatedAt().format(ISO_FORMATTER) : null);

                String idStr = thresh != null ? "cfg-" + thresh.getId() : "item-" + wh.getId() + "-" + p.getId();

                MinStockAlertResponse item = MinStockAlertResponse.builder()
                        .id(idStr)
                        .productId(p.getId())
                        .productSku(p.getSku())
                        .productName(p.getName())
                        .category(p.getCategory() != null ? p.getCategory() : "Hàng hóa chung")
                        .unit(p.getBaseUnit() != null ? p.getBaseUnit() : "Cái")
                        .warehouseCode(wh.getCode())
                        .warehouseName(wh.getName())
                        .minThreshold(stripZeros(minThreshold))
                        .currentStock(stripZeros(physicalStock))
                        .reservedStock(stripZeros(reservedStock))
                        .availableStock(stripZeros(availableStock))
                        .isBelowThreshold(isBelow)
                        .deficitQuantity(stripZeros(deficit))
                        .severity(severity)
                        .suggestedReorderQuantity(stripZeros(suggestedReorder))
                        .supplierCode(supplierCode)
                        .supplierName(supplierName)
                        .lastRestockedDate(lastRestocked)
                        .updatedAt(updatedIso)
                        .build();

                result.add(item);
            }
        }

        // Sắp xếp: Ưu tiên CRITICAL -> WARNING -> SAFE, sau đó theo số lượng thiếu hụt giảm dần
        result.sort((a, b) -> {
            int sevCompare = severityRank(a.getSeverity()) - severityRank(b.getSeverity());
            if (sevCompare != 0) return sevCompare;
            int defCompare = b.getDeficitQuantity().compareTo(a.getDeficitQuantity());
            if (defCompare != 0) return defCompare;
            return a.getProductSku().compareToIgnoreCase(b.getProductSku());
        });

        return result;
    }

    /**
     * Khai báo / Cập nhật định mức tồn tối thiểu cho SKU tại kho (AC1).
     */
    @Transactional
    public MinStockAlertResponse updateMinThreshold(UpdateMinThresholdPayload payload, UserDetailsImpl actor) {
        if (payload.getMinThreshold() == null || payload.getMinThreshold().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_THRESHOLD",
                    "Định mức tồn tối thiểu không thể nhỏ hơn 0", "minThreshold");
        }

        if (actor != null && actor.getAuthorities() != null) {
            Set<String> roles = actor.getAuthorities().stream()
                    .map(a -> a.getAuthority())
                    .collect(Collectors.toSet());
            boolean canEdit = roles.contains("ROLE_ADMIN") || roles.contains("ROLE_WH_MANAGER");
            if (!canEdit) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                        "Chỉ Quản lý kho hoặc Quản trị viên mới có quyền thiết lập định mức tồn tối thiểu");
            }
        }

        Product product = productRepository.findBySkuIgnoreCase(payload.getProductSku().trim())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy sản phẩm có SKU: " + payload.getProductSku()));

        Warehouse warehouse = warehouseRepository.findByCodeIgnoreCase(payload.getWarehouseCode().trim())
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy kho hàng có mã: " + payload.getWarehouseCode()));

        Set<Long> allowed = allowedWarehouseIds(actor);
        if (allowed != null && !allowed.contains(warehouse.getId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                    "Bạn không có quyền quản lý kho: " + warehouse.getName());
        }

        MinStockThreshold threshold = minStockThresholdRepository
                .findByProduct_IdAndWarehouse_Id(product.getId(), warehouse.getId())
                .orElseGet(() -> MinStockThreshold.builder()
                        .product(product)
                        .warehouse(warehouse)
                        .build());

        threshold.setMinThreshold(payload.getMinThreshold());
        threshold = minStockThresholdRepository.save(threshold);

        log.info("S5-09: Đã cập nhật định mức tồn tối thiểu cho SKU {} tại kho {}: {}",
                product.getSku(), warehouse.getCode(), payload.getMinThreshold());

        // Lấy tồn kho thực tế hiện tại
        Inventory inv = inventoryRepository.findByWarehouseAndProduct(warehouse, product).orElse(null);
        BigDecimal physicalStock = inv != null && inv.getPhysicalStock() != null ? inv.getPhysicalStock() : BigDecimal.ZERO;
        BigDecimal reservedStock = inv != null && inv.getReservedStock() != null ? inv.getReservedStock() : BigDecimal.ZERO;
        BigDecimal availableStock = physicalStock.subtract(reservedStock);

        BigDecimal minThreshold = threshold.getMinThreshold();
        boolean isBelow = minThreshold.compareTo(BigDecimal.ZERO) > 0 && physicalStock.compareTo(minThreshold) < 0;
        BigDecimal deficit = isBelow ? minThreshold.subtract(physicalStock).max(BigDecimal.ZERO) : BigDecimal.ZERO;

        String severity;
        if (!isBelow) {
            severity = "SAFE";
        } else if (physicalStock.compareTo(BigDecimal.ZERO) <= 0
                || physicalStock.compareTo(minThreshold.multiply(new BigDecimal("0.5"))) < 0) {
            severity = "CRITICAL";
        } else {
            severity = "WARNING";
        }

        BigDecimal suggestedReorder = isBelow
                ? deficit.add(minThreshold.multiply(new BigDecimal("0.5"))).setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return MinStockAlertResponse.builder()
                .id("cfg-" + threshold.getId())
                .productId(product.getId())
                .productSku(product.getSku())
                .productName(product.getName())
                .category(product.getCategory() != null ? product.getCategory() : "Hàng hóa chung")
                .unit(product.getBaseUnit() != null ? product.getBaseUnit() : "Cái")
                .warehouseCode(warehouse.getCode())
                .warehouseName(warehouse.getName())
                .minThreshold(stripZeros(minThreshold))
                .currentStock(stripZeros(physicalStock))
                .availableStock(stripZeros(availableStock))
                .isBelowThreshold(isBelow)
                .deficitQuantity(stripZeros(deficit))
                .severity(severity)
                .suggestedReorderQuantity(stripZeros(suggestedReorder))
                .updatedAt(threshold.getUpdatedAt() != null ? threshold.getUpdatedAt().format(ISO_FORMATTER) : null)
                .build();
    }

    /**
     * Lấy danh sách cấu hình định mức phân trang (mặc định 50 bản ghi/trang để tải nhanh).
     */
    @Transactional(readOnly = true)
    public PageResponse<MinStockAlertResponse> fetchMinStockConfigsPage(
            String warehouseCode,
            Boolean belowThresholdOnly,
            String keyword,
            int page,
            int size,
            UserDetailsImpl actor) {
        return fetchMinStockConfigsPage(warehouseCode, belowThresholdOnly, null, keyword, page, size, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<MinStockAlertResponse> fetchMinStockConfigsPage(
            String warehouseCode,
            Boolean belowThresholdOnly,
            String status,
            String keyword,
            int page,
            int size,
            UserDetailsImpl actor) {
        return fetchMinStockConfigsPage(warehouseCode, null, belowThresholdOnly, status, keyword, page, size, actor);
    }

    @Transactional(readOnly = true)
    public PageResponse<MinStockAlertResponse> fetchMinStockConfigsPage(
            String warehouseCode,
            String category,
            Boolean belowThresholdOnly,
            String status,
            String keyword,
            int page,
            int size,
            UserDetailsImpl actor) {
        List<MinStockAlertResponse> all = fetchMinStockConfigs(warehouseCode, category, belowThresholdOnly, status, keyword, actor);
        int total = all.size();
        int s = Math.max(1, size);
        int totalPages = (int) Math.ceil((double) total / s);
        int p = Math.max(0, page);
        int start = Math.min(p * s, total);
        int end = Math.min(start + s, total);
        List<MinStockAlertResponse> content = new ArrayList<>(all.subList(start, end));
        return new PageResponse<>(content, p, s, total, totalPages);
    }

    /**
     * Subtask SCRUM-164: BE: API check SKU dưới định mức.
     */
    @Transactional(readOnly = true)
    public List<MinStockAlertResponse> checkBelowThreshold(String warehouseCode, UserDetailsImpl actor) {
        return fetchMinStockConfigs(warehouseCode, true, null, actor);
    }

    /**
     * Thống kê tổng hợp cảnh báo cho Dashboard kho.
     */
    @Transactional(readOnly = true)
    public MinStockSummaryResponse getSummary(String warehouseCode, UserDetailsImpl actor) {
        List<MinStockAlertResponse> all = fetchMinStockConfigs(warehouseCode, false, null, actor);
        List<MinStockAlertResponse> alerts = all.stream()
                .filter(MinStockAlertResponse::isBelowThreshold)
                .toList();
        List<MinStockAlertResponse> critical = alerts.stream()
                .filter(a -> "CRITICAL".equalsIgnoreCase(a.getSeverity()))
                .toList();
        List<MinStockAlertResponse> warning = alerts.stream()
                .filter(a -> "WARNING".equalsIgnoreCase(a.getSeverity()))
                .toList();

        return MinStockSummaryResponse.builder()
                .totalSkuCount(all.size())
                .safeCount(all.size() - alerts.size())
                .totalAlerts(alerts.size())
                .criticalCount(critical.size())
                .warningCount(warning.size())
                .criticalItems(alerts.stream().limit(10).toList())
                .build();
    }

    private static int severityRank(String sev) {
        if ("CRITICAL".equalsIgnoreCase(sev)) return 1;
        if ("WARNING".equalsIgnoreCase(sev)) return 2;
        return 3;
    }

    private static BigDecimal stripZeros(BigDecimal v) {
        if (v == null) return BigDecimal.ZERO;
        BigDecimal stripped = v.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    private Set<Long> allowedWarehouseIds(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return null;
        }
        Set<String> roles = actor.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .collect(Collectors.toSet());

        boolean broad = roles.contains("ROLE_ADMIN")
                || roles.contains("ROLE_ACCOUNTANT") || roles.contains("ROLE_SALES_MANAGER");
        if (broad) {
            return null;
        }

        // Quản lý kho và Nhân viên kho: lọc theo danh sách kho được phân công
        if (roles.contains("ROLE_WAREHOUSE") || roles.contains("ROLE_WH_MANAGER")) {
            Set<Long> userWhIds = userRepository.findById(actor.getId())
                    .map(u -> u.getWarehouses().stream().map(Warehouse::getId).collect(Collectors.toSet()))
                    .orElse(Collections.emptySet());

            if (!userWhIds.isEmpty()) {
                return userWhIds;
            }

            // Fallback: nếu chưa được gán kho cụ thể trong DB (dữ liệu mẫu), mặc định gắn với Kho Tổng Miền Bắc (WH-MB01)
            Set<Long> fallback = new HashSet<>();
            warehouseRepository.findByCodeIgnoreCase("WH-MB01")
                    .ifPresent(wh -> fallback.add(wh.getId()));
            return fallback.isEmpty() ? null : fallback;
        }

        return null;
    }
}
