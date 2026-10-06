package com.erp.backend.repository;

import com.erp.backend.entity.CustomerGroup;
import com.erp.backend.entity.PriceList;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/** S2-10: Lọc bảng giá phía server theo nhóm khách hàng, trạng thái, từ khoá (mã / tên / ghi chú). */
public final class PriceListSpecifications {

    private PriceListSpecifications() {
    }

    public static Specification<PriceList> filter(CustomerGroup customerGroup, String status, String keyword) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (customerGroup != null) {
                predicates.add(cb.equal(root.get("customerGroup"), customerGroup));
            }
            if (StringUtils.hasText(status)) {
                predicates.add(cb.equal(root.get("status"), status.trim().toUpperCase()));
            }
            if (StringUtils.hasText(keyword)) {
                String like = "%" + escapeLike(keyword.trim().toLowerCase()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("code")), like, '\\'),
                        cb.like(cb.lower(root.get("name")), like, '\\'),
                        cb.like(cb.lower(cb.coalesce(root.<String>get("note"), "")), like, '\\')));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
