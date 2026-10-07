package com.erp.backend.repository;

import com.erp.backend.entity.AuditLog;
import com.erp.backend.entity.AuditModule;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S2-04: Lọc nhật ký hệ thống:
 * - Lọc theo người dùng (actorId)
 * - Lọc theo loại đối tượng / phân hệ (module, targetType)
 * - Lọc theo khoảng thời gian (startDate, endDate)
 * - Tìm kiếm theo từ khóa (keyword)
 */
public final class AuditLogSpecifications {

    private AuditLogSpecifications() {
    }

    public static Specification<AuditLog> filter(
            String keyword,
            AuditModule module,
            String targetType,
            Long actorId,
            LocalDateTime startDate,
            LocalDateTime endDate,
            String action) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("targetCode")), like),
                        cb.like(cb.lower(root.get("actorUsername")), like),
                        cb.like(cb.lower(root.get("actorFullName")), like),
                        cb.like(cb.lower(root.get("reason")), like),
                        cb.like(cb.lower(root.get("action")), like)));
            }

            if (module != null) {
                predicates.add(cb.equal(root.get("module"), module));
            }

            if (StringUtils.hasText(targetType)) {
                predicates.add(cb.equal(cb.lower(root.get("targetType")), targetType.trim().toLowerCase()));
            }

            if (actorId != null) {
                predicates.add(cb.equal(root.get("actorId"), actorId));
            }

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), startDate));
            }

            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), endDate));
            }

            if (StringUtils.hasText(action)) {
                predicates.add(cb.equal(cb.upper(root.get("action")), action.trim().toUpperCase()));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
