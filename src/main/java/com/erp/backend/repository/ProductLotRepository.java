package com.erp.backend.repository;

import com.erp.backend.entity.ProductLot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface ProductLotRepository extends JpaRepository<ProductLot, Long>, JpaSpecificationExecutor<ProductLot> {

    Optional<ProductLot> findByProduct_IdAndWarehouse_IdAndBatchNumber(Long productId, Long warehouseId, String batchNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM ProductLot l WHERE l.product.id = :productId AND l.warehouse.id = :warehouseId AND l.batchNumber = :batchNumber")
    Optional<ProductLot> findByProductAndWarehouseAndBatchNumberForUpdate(
            @Param("productId") Long productId,
            @Param("warehouseId") Long warehouseId,
            @Param("batchNumber") String batchNumber);

    boolean existsByProduct_IdAndWarehouse_IdAndBatchNumber(Long productId, Long warehouseId, String batchNumber);

    List<ProductLot> findByProduct_IdAndWarehouse_Id(Long productId, Long warehouseId);

    List<ProductLot> findByProduct_IdAndWarehouse_IdOrderByExpiredDateAsc(Long productId, Long warehouseId);

    List<ProductLot> findByProduct_Id(Long productId);

    List<ProductLot> findByProduct_Sku(String sku);

    List<ProductLot> findByWarehouse_Id(Long warehouseId);

    List<ProductLot> findByBatchNumber(String batchNumber);

    /** S8-01: Số lô có tồn tại cho SKU (dùng khi dòng đơn cũ chưa lưu lô đã xuất). */
    boolean existsByProduct_IdAndBatchNumberIgnoreCase(Long productId, String batchNumber);

    List<ProductLot> findByExpiredDateBefore(LocalDate date);

    List<ProductLot> findByWarehouse_IdAndExpiredDateBefore(Long warehouseId, LocalDate date);

    List<ProductLot> findByExpiredDateBetween(LocalDate start, LocalDate end);

    List<ProductLot> findByWarehouse_IdAndExpiredDateBetween(Long warehouseId, LocalDate start, LocalDate end);

    boolean existsBySupplier_Id(Long supplierId);
}
