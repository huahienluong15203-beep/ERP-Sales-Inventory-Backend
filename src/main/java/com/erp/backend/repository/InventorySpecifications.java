package com.erp.backend.repository;

import com.erp.backend.dto.inventory.StockLedgerCriteria;
import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.Warehouse;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** S5-05: Lọc sổ tồn ngay trong câu truy vấn DB (dùng chung cho danh sách phân trang và phần tổng). */
public final class InventorySpecifications {

    public static final String IN_STOCK = "IN_STOCK";
    public static final String FULLY_RESERVED = "FULLY_RESERVED";
    public static final String OUT_OF_STOCK = "OUT_OF_STOCK";

    private InventorySpecifications() {
    }

    public static Specification<Inventory> ledger(StockLedgerCriteria c) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            Join<Inventory, Product> product = root.join("product");
            Join<Inventory, Warehouse> warehouse = root.join("warehouse");
            if (c.allowedWarehouseIds() != null) {
                if (c.allowedWarehouseIds().isEmpty()) {
                    return cb.disjunction();
                }
                ps.add(warehouse.get("id").in(c.allowedWarehouseIds()));
            }
            if (c.warehouseId() != null) {
                ps.add(cb.equal(warehouse.get("id"), c.warehouseId()));
            }
            if (c.categoryIds() != null) {
                if (c.categoryIds().isEmpty()) {
                    return cb.disjunction();
                }
                ps.add(product.get("productCategory").get("id").in(c.categoryIds()));
            }
            if (StringUtils.hasText(c.stockStatus())) {
                Expression<BigDecimal> physical = root.get("physicalStock");
                Expression<BigDecimal> available = cb.diff(physical, root.<BigDecimal>get("reservedStock"));
                switch (c.stockStatus()) {
                    case OUT_OF_STOCK -> ps.add(cb.le(physical, BigDecimal.ZERO));
                    case FULLY_RESERVED -> ps.add(cb.and(cb.gt(physical, BigDecimal.ZERO), cb.le(available, BigDecimal.ZERO)));
                    case IN_STOCK -> ps.add(cb.gt(available, BigDecimal.ZERO));
                    default -> {
                        // đã kiểm ở service
                    }
                }
            }
            if (StringUtils.hasText(c.keyword())) {
                String like = "%" + c.keyword().trim().toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(product.get("sku")), like), cb.like(cb.lower(product.get("name")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
