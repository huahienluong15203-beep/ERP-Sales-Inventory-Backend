package com.erp.backend.repository;

import com.erp.backend.entity.Customer;
import com.erp.backend.entity.CustomerGroup;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * S3-08: Điều kiện tìm kiếm đại lý.
 * - keyword: mã, tên (có dấu hoặc không dấu), số điện thoại
 * - lọc theo khu vực, nhóm khách hàng, người phụ trách, trạng thái
 * - S3-07: lọc đại lý bị khoá / đang mở giao dịch (transactionLocked)
 */
public final class CustomerSpecifications {

    private CustomerSpecifications() {
    }

    public static Specification<Customer> search(String keyword, Long regionId, CustomerGroup customerGroup,
                                                 Long salesRepId, String status) {
        return search(keyword, regionId, customerGroup, salesRepId, status, null);
    }

    /**
     * @param transactionLocked true = chỉ đại lý bị khoá giao dịch; false = chỉ đại lý đang mở; null = không lọc
     */
    public static Specification<Customer> search(String keyword, Long regionId, CustomerGroup customerGroup,
                                                 Long salesRepId, String status, Boolean transactionLocked) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (StringUtils.hasText(keyword)) {
                String rawLike = "%" + keyword.trim().toLowerCase() + "%";
                String unaccentLike = "%" + UserSpecifications.removeAccents(keyword) + "%";

                // Dùng hàm unaccent của PostgreSQL để tìm tên không dấu (đã bật ở ReferenceDataInitializer)
                Expression<String> unaccentName = cb.function("unaccent", String.class, cb.lower(root.get("name")));

                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), rawLike),
                        cb.like(cb.lower(root.get("name")), rawLike),
                        cb.like(unaccentName, unaccentLike),
                        cb.like(root.get("phone"), "%" + keyword.trim() + "%")));
            }

            if (regionId != null) {
                predicates.add(cb.equal(root.get("region").get("id"), regionId));
            }

            if (customerGroup != null) {
                predicates.add(cb.equal(root.get("customerGroup"), customerGroup));
            }

            if (salesRepId != null) {
                predicates.add(cb.equal(root.get("salesRep").get("id"), salesRepId));
            }

            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }

            if (transactionLocked != null) {
                Path<Boolean> locked = root.get("transactionLocked");
                // Dữ liệu cũ có thể để null -> coi như đang mở giao dịch
                predicates.add(transactionLocked
                        ? cb.isTrue(locked)
                        : cb.or(cb.isNull(locked), cb.isFalse(locked)));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
