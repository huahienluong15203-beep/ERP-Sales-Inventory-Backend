package com.erp.backend.service;

import com.erp.backend.dto.inventory.StockLedgerCriteria;
import com.erp.backend.dto.inventory.StockLedgerRow;
import com.erp.backend.dto.inventory.StockLedgerSummary;
import com.erp.backend.dto.inventory.WarehouseOptionResponse;
import com.erp.backend.dto.user.PageResponse;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.InventoryRepository;
import com.erp.backend.repository.InventorySpecifications;
import com.erp.backend.repository.ProductCategoryRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.*;

/**
 * S5-05: Sổ tồn kho 3 cột theo từng SKU và từng kho.
 * - Ba cột tách bạch (ĐVT cơ sở): tồn thực tế, tồn đang giữ chỗ cho đơn chưa xuất kho, tồn khả dụng = thực tế - giữ chỗ.
 * - Lọc theo kho, nhóm hàng (gồm cả nhóm con), trạng thái tồn; tìm nhanh theo mã / tên sản phẩm. Lọc trong DB.
 * - Nhân viên kho (WAREHOUSE) chỉ xem các kho mình được gắn.
 */
@Service
@RequiredArgsConstructor
public class StockLedgerService {

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;
    static final long MAX_OFFSET = 1_000_000L;
    static final Map<String, String> STATUS_LABELS = Map.of(
            InventorySpecifications.IN_STOCK, "Còn hàng",
            InventorySpecifications.FULLY_RESERVED, "Đã giữ chỗ hết",
            InventorySpecifications.OUT_OF_STOCK, "Hết hàng");
    // Chỉ cho sắp theo các cột này (tránh lỗi 500 khi gửi tên cột lạ)
    static final Map<String, String> SORT_FIELDS = Map.of(
            "sku", "product.sku",
            "productName", "product.name",
            "physicalStock", "physicalStock",
            "reservedStock", "reservedStock",
            "updatedAt", "updatedAt",
            "warehouseCode", "warehouse.code");

    private final InventoryRepository inventoryRepository;
    private final WarehouseRepository warehouseRepository;
    private final ProductCategoryRepository categoryRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public PageResponse<StockLedgerRow> search(Long warehouseId, Long categoryId, String stockStatus, String keyword,
                                               String sort, int page, int size, UserDetailsImpl actor) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        if ((long) safePage * safeSize > MAX_OFFSET) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PAGE_TOO_LARGE", "Số trang quá lớn", "page");
        }
        StockLedgerCriteria criteria = criteria(warehouseId, categoryId, stockStatus, keyword, actor);
        return PageResponse.of(inventoryRepository.findAll(InventorySpecifications.ledger(criteria),
                        PageRequest.of(safePage, safeSize, sort(sort)))
                .map(StockLedgerService::toRow));
    }

    @Transactional(readOnly = true)
    public StockLedgerSummary summary(Long warehouseId, Long categoryId, String stockStatus, String keyword,
                                      UserDetailsImpl actor) {
        return inventoryRepository.summarize(InventorySpecifications.ledger(
                criteria(warehouseId, categoryId, stockStatus, keyword, actor)));
    }

    /** Danh sách kho cho ô chọn; NV kho chỉ thấy kho mình được gắn. */
    @Transactional(readOnly = true)
    public List<WarehouseOptionResponse> warehouses(String status, UserDetailsImpl actor) {
        List<Warehouse> list = StringUtils.hasText(status)
                ? warehouseRepository.findByStatusOrderByNameAsc(status.trim().toUpperCase())
                : warehouseRepository.findAll(Sort.by("name"));
        Set<Long> allowed = allowedWarehouseIds(actor);
        return list.stream()
                .filter(w -> allowed == null || allowed.contains(w.getId()))
                .<WarehouseOptionResponse>map(w -> new WarehouseOptionResponse(w.getId(), w.getCode(), w.getName(), w.getAddress(), w.getStatus()))
                .toList();
    }

    StockLedgerCriteria criteria(Long warehouseId, Long categoryId, String stockStatus, String keyword,
                                 UserDetailsImpl actor) {
        String status = StringUtils.hasText(stockStatus) ? stockStatus.trim().toUpperCase() : null;
        if (status != null && !STATUS_LABELS.containsKey(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STOCK_STATUS",
                    "Trạng thái tồn không hợp lệ (IN_STOCK, FULLY_RESERVED, OUT_OF_STOCK)", "stockStatus");
        }
        if (warehouseId != null && !warehouseRepository.existsById(warehouseId)) {
            throw BusinessException.notFound("Không tìm thấy kho");
        }
        Set<Long> categoryIds = null;
        if (categoryId != null) {
            categoryIds = categoryWithDescendants(categoryId);
        }
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (kw != null && kw.length() > 100) {
            kw = kw.substring(0, 100);
        }
        return new StockLedgerCriteria(warehouseId, allowedWarehouseIds(actor), categoryIds, status, kw);
    }

    /** Nhóm hàng đã chọn kèm mọi nhóm con (cây tối đa vài cấp, số nhóm nhỏ nên tính trong bộ nhớ). */
    Set<Long> categoryWithDescendants(Long categoryId) {
        List<ProductCategory> all = categoryRepository.findAllByOrderByLevelAscNameAsc();
        if (all.stream().noneMatch(c -> categoryId.equals(c.getId()))) {
            throw BusinessException.notFound("Không tìm thấy nhóm hàng");
        }
        Map<Long, List<Long>> children = new HashMap<>();
        for (ProductCategory c : all) {
            if (c.getParent() != null) {
                children.computeIfAbsent(c.getParent().getId(), k -> new ArrayList<>()).add(c.getId());
            }
        }
        Set<Long> result = new LinkedHashSet<>();
        Deque<Long> queue = new ArrayDeque<>(List.of(categoryId));
        while (!queue.isEmpty()) {
            Long id = queue.poll();
            if (result.add(id)) {
                queue.addAll(children.getOrDefault(id, List.of()));
            }
        }
        return result;
    }

    /** null = được xem mọi kho; NV kho (không kiêm vai trò quản lý) chỉ xem kho được gắn. */
    Set<Long> allowedWarehouseIds(UserDetailsImpl actor) {
        if (actor == null || actor.getAuthorities() == null) {
            return Set.of();
        }
        Set<String> roles = new HashSet<>();
        actor.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        boolean broad = roles.contains("ROLE_ADMIN") || roles.contains("ROLE_WH_MANAGER")
                || roles.contains("ROLE_ACCOUNTANT") || roles.contains("ROLE_SALES_MANAGER");
        if (broad || !roles.contains("ROLE_WAREHOUSE")) {
            return null;
        }
        return userRepository.findById(actor.getId())
                .map(u -> {
                    Set<Long> ids = new HashSet<>();
                    u.getWarehouses().forEach(w -> ids.add(w.getId()));
                    return ids;
                })
                .orElse(Set.of());
    }

    private static Sort sort(String sort) {
        Sort fallback = Sort.by("warehouse.code").ascending().and(Sort.by("product.sku").ascending());
        if (!StringUtils.hasText(sort)) {
            return fallback;
        }
        String[] parts = sort.split(",");
        String property = SORT_FIELDS.get(parts[0].trim());
        if (property == null) {
            return fallback;
        }
        Sort.Direction dir = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC : Sort.Direction.ASC;
        return Sort.by(dir, property).and(Sort.by("id").ascending());
    }

    static StockLedgerRow toRow(Inventory i) {
        Product p = i.getProduct();
        Warehouse w = i.getWarehouse();
        BigDecimal physical = nz(i.getPhysicalStock());
        BigDecimal reserved = nz(i.getReservedStock());
        BigDecimal available = physical.subtract(reserved);
        String status = stockStatus(physical, available);
        ProductCategory cat = p.getProductCategory();
        return new StockLedgerRow(i.getId(), w.getId(), w.getCode(), w.getName(), p.getId(), p.getSku(), p.getName(),
                p.getStatus(), cat != null ? cat.getId() : null, cat != null ? cat.getName() : p.getCategory(),
                p.getBaseUnit(), strip(physical), strip(reserved), strip(available), status, STATUS_LABELS.get(status),
                i.getUpdatedAt());
    }

    static String stockStatus(BigDecimal physical, BigDecimal available) {
        if (physical.signum() <= 0) {
            return InventorySpecifications.OUT_OF_STOCK;
        }
        return available.signum() <= 0 ? InventorySpecifications.FULLY_RESERVED : InventorySpecifications.IN_STOCK;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
