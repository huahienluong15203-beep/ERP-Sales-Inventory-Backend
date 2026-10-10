package com.erp.backend.repository;

import com.erp.backend.entity.MinStockThreshold;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MinStockThresholdRepository extends JpaRepository<MinStockThreshold, Long> {

    Optional<MinStockThreshold> findByProduct_IdAndWarehouse_Id(Long productId, Long warehouseId);

    Optional<MinStockThreshold> findByWarehouse_CodeIgnoreCaseAndProduct_SkuIgnoreCase(String warehouseCode, String productSku);

    List<MinStockThreshold> findByWarehouse_Id(Long warehouseId);

    List<MinStockThreshold> findByWarehouse_CodeIgnoreCase(String warehouseCode);

    List<MinStockThreshold> findByProduct_Id(Long productId);
}
