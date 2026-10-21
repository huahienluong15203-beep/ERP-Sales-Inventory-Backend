package com.erp.backend.repository;

import com.erp.backend.entity.StockTransferLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockTransferLineRepository extends JpaRepository<StockTransferLine, Long> {

    List<StockTransferLine> findByStockTransfer_Id(Long transferId);

    List<StockTransferLine> findByProduct_Id(Long productId);
}
