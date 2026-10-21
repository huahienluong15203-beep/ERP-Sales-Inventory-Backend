package com.erp.backend.repository;

import com.erp.backend.dto.inventory.StockLedgerSummary;
import com.erp.backend.entity.Inventory;
import org.springframework.data.jpa.domain.Specification;

/** S5-05: Tính tổng sổ tồn theo bộ lọc trong một câu SELECT. */
public interface InventoryLedgerRepository {

    StockLedgerSummary summarize(Specification<Inventory> spec);
}
