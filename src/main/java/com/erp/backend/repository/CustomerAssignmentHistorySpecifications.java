package com.erp.backend.repository;

import com.erp.backend.entity.CustomerAssignmentHistory;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Điều kiện lọc và tìm kiếm lịch sử phân công & chuyển giao địa bàn.
 */
public final class CustomerAssignmentHistorySpecifications {

    private CustomerAssignmentHistorySpecifications() {
    }

    public static Specification<CustomerAssignmentHistory> search(
            String keyword,
            Long customerId,
            Long fromSalesRepId,
            Long toSalesRepId,
            String changeType,
            Long restrictedSalesRepId,
            LocalDateTime startDate,
            LocalDateTime endDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // S3-06: LEFT JOIN người chuyển / người nhận. Dòng "tạo đại lý" không có người chuyển (fromSalesRep = null),
            // dùng root.get("fromSalesRep").get(...) sẽ thành INNER JOIN và làm mất dòng đó khi tìm theo từ khoá.
            Join<Object, Object> fromRep = null;
            Join<Object, Object> toRep = null;
            if (StringUtils.hasText(keyword) || restrictedSalesRepId != null) {
                fromRep = root.join("fromSalesRep", JoinType.LEFT);
                toRep = root.join("toSalesRep", JoinType.LEFT);
            }

            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("customer").get("code")), like),
                        cb.like(cb.lower(root.get("customer").get("name")), like),
                        cb.like(cb.lower(root.get("reason")), like),
                        cb.like(cb.lower(cb.coalesce(fromRep.<String>get("fullName"), "")), like),
                        cb.like(cb.lower(cb.coalesce(toRep.<String>get("fullName"), "")), like)));
            }

            if (customerId != null) {
                predicates.add(cb.equal(root.get("customer").get("id"), customerId));
            }

            if (fromSalesRepId != null) {
                predicates.add(cb.equal(root.get("fromSalesRep").get("id"), fromSalesRepId));
            }

            if (toSalesRepId != null) {
                predicates.add(cb.equal(root.get("toSalesRep").get("id"), toSalesRepId));
            }

            if (StringUtils.hasText(changeType) && !"ALL".equalsIgnoreCase(changeType)) {
                predicates.add(cb.equal(root.get("changeType"), changeType.trim().toUpperCase()));
            }

            if (restrictedSalesRepId != null) {
                predicates.add(cb.or(
                        cb.equal(fromRep.get("id"), restrictedSalesRepId),
                        cb.equal(toRep.get("id"), restrictedSalesRepId)));
            }

            if (startDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("changedAt"), startDate));
            }

            if (endDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("changedAt"), endDate));
            }

            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
