package com.erp.backend.repository;

import com.erp.backend.entity.ProductLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductLotRepository extends JpaRepository<ProductLot, Long>, JpaSpecificationExecutor<ProductLot> {

    Optional<ProductLot> findByProduct_IdAndWarehouse_IdAndBatchNumber(Long productId, Long warehouseId, String batchNumber);

    boolean existsByProduct_IdAndWarehouse_IdAndBatchNumber(Long productId, Long warehouseId, String batchNumber);

    List<ProductLot> findByProduct_IdAndWarehouse_Id(Long productId, Long warehouseId);

    List<ProductLot> findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(Long productId, Long warehouseId);

    List<ProductLot> findByProduct_Id(Long productId);

    List<ProductLot> findByProduct_Sku(String sku);

    List<ProductLot> findByWarehouse_Id(Long warehouseId);

    List<ProductLot> findByBatchNumber(String batchNumber);

    List<ProductLot> findByExpiredDateBefore(LocalDate date);

    List<ProductLot> findByWarehouse_IdAndExpiredDateBefore(Long warehouseId, LocalDate date);

    List<ProductLot> findByExpiredDateBetween(LocalDate start, LocalDate end);

    List<ProductLot> findByWarehouse_IdAndExpiredDateBetween(Long warehouseId, LocalDate start, LocalDate end);

    boolean existsBySupplier_Id(Long supplierId);
}
