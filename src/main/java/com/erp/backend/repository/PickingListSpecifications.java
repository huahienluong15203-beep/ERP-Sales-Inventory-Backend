package com.erp.backend.repository;

import com.erp.backend.entity.PickingList;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Specification cho việc tìm kiếm, lọc và phân trang Phiếu soạn hàng (S6-03).
 */
public final class PickingListSpecifications {

    private PickingListSpecifications() {
    }

    public static Specification<PickingList> filter(
            Collection<Long> allowedWarehouseIds,
            Long warehouseId,
            String status,
            Long pickerId,
            String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            // 1. Phân quyền kho
            if (allowedWarehouseIds != null) {
                if (allowedWarehouseIds.isEmpty()) {
                    return cb.disjunction();
                }
                predicates.add(root.get("warehouse").get("id").in(allowedWarehouseIds));
            }

            // 2. Lọc theo kho cụ thể
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }

            // 3. Lọc theo trạng thái
            if (StringUtils.hasText(status) && !"ALL".equalsIgnoreCase(status.trim())) {
                predicates.add(cb.equal(cb.upper(root.get("status")), status.trim().toUpperCase()));
            }

            // 4. Lọc theo người soạn (pickerId)
            if (pickerId != null) {
                predicates.add(cb.equal(root.get("picker").get("id"), pickerId));
            }

            // 5. Tìm kiếm từ khóa: mã phiếu, ghi chú, tên người soạn, username người tạo
            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("note")), like),
                        cb.like(cb.lower(root.get("pickerName")), like),
                        cb.like(cb.lower(root.get("createdByUsername")), like)
                ));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
