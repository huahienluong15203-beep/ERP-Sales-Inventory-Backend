package com.erp.backend.repository;

import com.erp.backend.dto.inventory.StockLedgerSummary;
import com.erp.backend.entity.Inventory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Spring Data tự gắn class này vào InventoryRepository (tên = interface + "Impl"). */
public class InventoryLedgerRepositoryImpl implements InventoryLedgerRepository {

    @PersistenceContext
    private EntityManager em;

    @Override
    public StockLedgerSummary summarize(Specification<Inventory> spec) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<Inventory> root = query.from(Inventory.class);
        Predicate where = spec.toPredicate(root, query, cb);

        Expression<BigDecimal> physical = root.get("physicalStock");
        Expression<BigDecimal> reserved = root.get("reservedStock");
        Expression<BigDecimal> available = cb.diff(physical, reserved);
        Expression<Integer> one = cb.literal(1);
        Expression<Integer> zero = cb.literal(0);

        query.multiselect(
                cb.count(root),
                cb.sum(physical),
                cb.sum(reserved),
                cb.sum(cb.<Integer>selectCase().when(cb.gt(available, BigDecimal.ZERO), one).otherwise(zero)),
                cb.sum(cb.<Integer>selectCase().when(cb.and(cb.gt(physical, BigDecimal.ZERO),
                        cb.le(available, BigDecimal.ZERO)), one).otherwise(zero)),
                cb.sum(cb.<Integer>selectCase().when(cb.le(physical, BigDecimal.ZERO), one).otherwise(zero)));
        if (where != null) {
            query.where(where);
        }
        Object[] row = em.createQuery(query).getSingleResult();
        BigDecimal totalPhysical = decimal(row[1]);
        BigDecimal totalReserved = decimal(row[2]);
        return new StockLedgerSummary(count(row[0]), totalPhysical, totalReserved,
                totalPhysical.subtract(totalReserved), count(row[3]), count(row[4]), count(row[5]));
    }

    private static long count(Object v) {
        return v == null ? 0L : ((Number) v).longValue();
    }

    private static BigDecimal decimal(Object v) {
        BigDecimal d = v == null ? BigDecimal.ZERO : (v instanceof BigDecimal b ? b : new BigDecimal(v.toString()));
        BigDecimal stripped = d.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
