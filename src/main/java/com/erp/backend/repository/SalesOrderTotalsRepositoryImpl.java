package com.erp.backend.repository;

import com.erp.backend.dto.order.OrderTotalsResponse;
import com.erp.backend.entity.SalesOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;

/** Spring Data tự gắn class này vào SalesOrderRepository (tên = interface + "Impl"). */
public class SalesOrderTotalsRepositoryImpl implements SalesOrderTotalsRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public OrderTotalsResponse sumTotals(Specification<SalesOrder> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<SalesOrder> root = query.from(SalesOrder.class);
        Predicate where = spec.toPredicate(root, query, cb);
        query.multiselect(cb.count(root), cb.sum(root.<BigDecimal>get("totalAmount")));
        if (where != null) {
            query.where(where);
        }
        Object[] row = em.createQuery(query).getSingleResult();
        long count = row[0] != null ? ((Number) row[0]).longValue() : 0L;
        BigDecimal total = row[1] != null ? (BigDecimal) row[1] : BigDecimal.ZERO;
        return new OrderTotalsResponse(count, total.setScale(2, java.math.RoundingMode.HALF_UP));
    }
}
