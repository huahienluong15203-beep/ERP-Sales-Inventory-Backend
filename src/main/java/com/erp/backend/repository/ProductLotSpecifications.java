package com.erp.backend.repository;

import com.erp.backend.entity.Product;
import com.erp.backend.entity.ProductLot;
import com.erp.backend.entity.Supplier;
import com.erp.backend.entity.Warehouse;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Specification cho việc tìm kiếm, lọc và phân trang Lô hàng & Hạn sử dụng (S6-01).
 */
public final class ProductLotSpecifications {

    private ProductLotSpecifications() {
    }

    public static Specification<ProductLot> filter(
            Collection<Long> allowedWarehouseIds,
            Long warehouseId,
            Long productId,
            String keyword,
            String status,
            String expiryStatus,
            Integer nearExpiryDays) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Phân quyền kho: nếu actor chỉ được xem danh sách kho nhất định
            if (allowedWarehouseIds != null) {
                if (allowedWarehouseIds.isEmpty()) {
                    return cb.disjunction(); // không có quyền xem kho nào
                }
                predicates.add(root.get("warehouse").get("id").in(allowedWarehouseIds));
            }

            // 2. Lọc theo kho cụ thể
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }

            // 3. Lọc theo sản phẩm cụ thể
            if (productId != null) {
                predicates.add(cb.equal(root.get("product").get("id"), productId));
            }

            // 4. Lọc theo trạng thái lô hàng (ACTIVE, LOCKED)
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase()));
            }

            // 5. Tìm kiếm từ khóa: số lô, SKU sản phẩm, tên sản phẩm, tên NCC
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                Join<ProductLot, Product> productJoin = root.join("product", JoinType.LEFT);
                Join<ProductLot, Supplier> supplierJoin = root.join("supplier", JoinType.LEFT);

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("batchNumber")), like),
                        cb.like(cb.lower(productJoin.get("sku")), like),
                        cb.like(cb.lower(productJoin.get("name")), like),
                        cb.like(cb.lower(supplierJoin.get("name")), like)
                ));
            }

            // 6. Lọc theo tình trạng hạn dùng (S6-01)
            LocalDate today = LocalDate.now();
            int days = (nearExpiryDays != null && nearExpiryDays > 0) ? nearExpiryDays : 30;
            LocalDate nearExpiryDate = today.plusDays(days);

            if (StringUtils.hasText(expiryStatus)) {
                String expStatus = expiryStatus.trim().toUpperCase();
                switch (expStatus) {
                    case "EXPIRED":
                        predicates.add(cb.and(
                                cb.isNotNull(root.get("expiredDate")),
                                cb.lessThan(root.get("expiredDate"), today),
                                cb.greaterThan(root.get("quantity"), BigDecimal.ZERO)
                        ));
                        break;
                    case "NEAR_EXPIRATION":
                        predicates.add(cb.and(
                                cb.isNotNull(root.get("expiredDate")),
                                cb.greaterThanOrEqualTo(root.get("expiredDate"), today),
                                cb.lessThanOrEqualTo(root.get("expiredDate"), nearExpiryDate),
                                cb.greaterThan(root.get("quantity"), BigDecimal.ZERO)
                        ));
                        break;
                    case "SAFE":
                        predicates.add(cb.and(
                                cb.isNotNull(root.get("expiredDate")),
                                cb.greaterThan(root.get("expiredDate"), nearExpiryDate),
                                cb.greaterThan(root.get("quantity"), BigDecimal.ZERO)
                        ));
                        break;
                    case "OUT_OF_STOCK":
                        predicates.add(cb.or(
                                cb.isNull(root.get("quantity")),
                                cb.lessThanOrEqualTo(root.get("quantity"), BigDecimal.ZERO)
                        ));
                        break;
                    default:
                        break;
                }
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
