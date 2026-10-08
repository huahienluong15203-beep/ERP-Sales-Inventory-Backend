package com.erp.backend.repository;

import com.erp.backend.dto.order.OrderTotalsResponse;
import com.erp.backend.entity.SalesOrder;
import org.springframework.data.jpa.domain.Specification;

/** S4-07: Tính tổng số đơn và tổng tiền theo bộ lọc ngay trong DB (một câu SELECT count / sum). */
public interface SalesOrderTotalsRepository {

    OrderTotalsResponse sumTotals(Specification<SalesOrder> spec);
}
