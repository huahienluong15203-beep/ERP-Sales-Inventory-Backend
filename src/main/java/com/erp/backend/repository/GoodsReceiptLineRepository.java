package com.erp.backend.repository;

import com.erp.backend.entity.GoodsReceiptLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GoodsReceiptLineRepository extends JpaRepository<GoodsReceiptLine, Long> {

    List<GoodsReceiptLine> findByGoodsReceipt_Id(Long receiptId);

    List<GoodsReceiptLine> findByProduct_Id(Long productId);

    List<GoodsReceiptLine> findByBatchNumber(String batchNumber);
}
