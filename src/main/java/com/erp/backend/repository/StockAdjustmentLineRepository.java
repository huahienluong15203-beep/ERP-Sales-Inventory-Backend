package com.erp.backend.repository;

import com.erp.backend.entity.StockAdjustmentLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * S8-03: Repository quản lý dòng chi tiết phiếu điều chỉnh tồn kho.
 */
@Repository
public interface StockAdjustmentLineRepository extends JpaRepository<StockAdjustmentLine, Long> {

    List<StockAdjustmentLine> findByStockAdjustment_IdOrderByLineNoAsc(Long stockAdjustmentId);
}
