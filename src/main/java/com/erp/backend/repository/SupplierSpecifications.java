package com.erp.backend.repository;

import com.erp.backend.entity.Supplier;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** S2-09: Tìm nhà cung cấp theo mã, tên (có/không dấu), mã số thuế, số điện thoại; lọc theo trạng thái. */
public final class SupplierSpecifications {

    private SupplierSpecifications() {
    }

    public static Specification<Supplier> search(String keyword, String status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(keyword)) {
                String raw = keyword.trim();
                String rawLike = "%" + raw.toLowerCase() + "%";
                String unaccentLike = "%" + UserSpecifications.removeAccents(keyword) + "%";
                Expression<String> unaccentName = cb.function("unaccent", String.class, cb.lower(root.get("name")));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), rawLike),
                        cb.like(cb.lower(root.get("name")), rawLike),
                        cb.like(unaccentName, unaccentLike),
                        cb.like(root.get("taxCode"), "%" + raw + "%"),
                        cb.like(root.get("phone"), "%" + raw + "%")));
            }

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
