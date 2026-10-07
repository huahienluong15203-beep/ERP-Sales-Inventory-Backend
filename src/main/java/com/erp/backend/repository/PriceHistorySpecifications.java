package com.erp.backend.repository;

import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.entity.PriceHistory;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** S3-02: Lọc lịch sử giá theo SKU, bảng giá, nhóm khách hàng, khoảng ngày thay đổi. */
public final class PriceHistorySpecifications {

    private PriceHistorySpecifications() {
    }

    public static Specification<PriceHistory> filter(String productSku, Long priceListId, CustomerGroup customerGroup,
                                                     LocalDate fromDate, LocalDate toDate) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (StringUtils.hasText(productSku)) {
                predicates.add(cb.equal(cb.upper(root.get("productSku")), productSku.trim().toUpperCase()));
            }
            if (priceListId != null) {
                predicates.add(cb.equal(root.get("priceList").get("id"), priceListId));
            }
            if (customerGroup != null) {
                predicates.add(cb.equal(root.get("customerGroup"), customerGroup));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("changedAt"), fromDate.atStartOfDay()));
            }
            if (toDate != null) {
                predicates.add(cb.lessThan(root.get("changedAt"), toDate.plusDays(1).atStartOfDay()));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
