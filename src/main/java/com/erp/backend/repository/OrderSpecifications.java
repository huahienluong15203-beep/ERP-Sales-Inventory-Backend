package com.erp.backend.repository;

import com.erp.backend.dto.order.OrderSearchCriteria;
import com.erp.backend.entity.Customer;
import com.erp.backend.entity.SalesOrder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * S4-07 & S5-01: Điều kiện lọc đơn hàng, dùng chung cho danh sách (phân trang) và tính tổng.
 * - restrictedSalesRepId != null (NV kinh doanh): luôn chỉ lấy đơn của đại lý mình phụ trách.
 * - restrictedCustomerId != null (Đại lý ROLE_CUSTOMER): luôn chỉ lấy đơn của chính mình (S5-01).
 */
public final class OrderSpecifications {

    private OrderSpecifications() {
    }

    public static Specification<SalesOrder> search(OrderSearchCriteria c, Long restrictedSalesRepId) {
        return search(c, restrictedSalesRepId, null);
    }

    public static Specification<SalesOrder> search(OrderSearchCriteria c, Long restrictedSalesRepId, Long restrictedCustomerId) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            Join<SalesOrder, Customer> customer = root.join("customer");
            if (c.statuses() != null && !c.statuses().isEmpty()) {
                ps.add(root.get("status").in(c.statuses()));
            }
            if (restrictedCustomerId != null) {
                // S5-01: Đại lý chỉ thấy đơn của chính mình
                ps.add(cb.equal(customer.get("id"), restrictedCustomerId));
            } else if (c.customerId() != null) {
                ps.add(cb.equal(customer.get("id"), c.customerId()));
            }
            if (c.salesRepId() != null) {
                ps.add(cb.equal(customer.get("salesRep").get("id"), c.salesRepId()));
            }
            if (restrictedSalesRepId != null) {
                ps.add(cb.equal(customer.get("salesRep").get("id"), restrictedSalesRepId));
            }
            if (c.regionId() != null) {
                ps.add(cb.equal(customer.get("region").get("id"), c.regionId()));
            }
            if (c.fromDate() != null) {
                ps.add(cb.greaterThanOrEqualTo(root.get("createdAt"), c.fromDate().atStartOfDay()));
            }
            if (c.toDate() != null) {
                ps.add(cb.lessThan(root.get("createdAt"), c.toDate().plusDays(1).atStartOfDay()));
            }
            if (StringUtils.hasText(c.keyword())) {
                String like = "%" + c.keyword().trim().toLowerCase() + "%";
                ps.add(cb.or(cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(customer.get("code")), like),
                        cb.like(cb.lower(customer.get("name")), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }
}
