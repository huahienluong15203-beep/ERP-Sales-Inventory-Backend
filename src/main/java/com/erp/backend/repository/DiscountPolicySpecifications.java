package com.erp.backend.repository;

import com.erp.backend.entity.DiscountPolicy;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * S3-01: Lọc chính sách chiết khấu phía server.
 * status: ACTIVE = đang bật và chưa hết hạn; EXPIRED = đang bật nhưng đã qua ngày kết thúc; INACTIVE = đã ngừng.
 */
public final class DiscountPolicySpecifications {

    private DiscountPolicySpecifications() {
    }

    public static Specification<DiscountPolicy> filter(String status, String scope, String keyword, LocalDate today) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(status)) {
                String st = status.trim().toUpperCase();
                switch (st) {
                    case "ACTIVE" -> {
                        predicates.add(cb.equal(root.get("status"), "ACTIVE"));
                        predicates.add(cb.or(cb.isNull(root.get("endDate")),
                                cb.greaterThanOrEqualTo(root.<LocalDate>get("endDate"), today)));
                    }
                    case "EXPIRED" -> {
                        predicates.add(cb.equal(root.get("status"), "ACTIVE"));
                        predicates.add(cb.lessThan(root.<LocalDate>get("endDate"), today));
                    }
                    default -> predicates.add(cb.equal(root.get("status"), st));
                }
            }
            if (StringUtils.hasText(scope)) {
                predicates.add(cb.equal(root.get("scope"), scope.trim().toUpperCase()));
            }
            if (StringUtils.hasText(keyword)) {
                String like = "%" + PriceListSpecifications.escapeLike(keyword.trim().toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("name")), like, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
