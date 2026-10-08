package com.erp.backend.repository;

import com.erp.backend.entity.CustomerAssignmentHistory;
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

            if (StringUtils.hasText(keyword)) {
                String like = "%" + keyword.trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("customer").get("code")), like),
                        cb.like(cb.lower(root.get("customer").get("name")), like),
                        cb.like(cb.lower(root.get("reason")), like),
                        cb.like(cb.lower(root.get("fromSalesRep").get("fullName")), like),
                        cb.like(cb.lower(root.get("toSalesRep").get("fullName")), like)));
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
                        cb.equal(root.get("fromSalesRep").get("id"), restrictedSalesRepId),
                        cb.equal(root.get("toSalesRep").get("id"), restrictedSalesRepId)));
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
