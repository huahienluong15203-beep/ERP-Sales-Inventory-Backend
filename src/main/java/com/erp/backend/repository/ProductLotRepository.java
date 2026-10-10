package com.erp.backend.repository;

import com.erp.backend.entity.ProductLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductLotRepository extends JpaRepository<ProductLot, Long> {

    Optional<ProductLot> findByProduct_IdAndWarehouse_IdAndBatchNumber(Long productId, Long warehouseId, String batchNumber);

    List<ProductLot> findByProduct_IdAndWarehouse_Id(Long productId, Long warehouseId);
}
