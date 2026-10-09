package com.erp.backend.repository;

import com.erp.backend.entity.Inventory;
import com.erp.backend.entity.Product;
import com.erp.backend.entity.Warehouse;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface InventoryRepository extends JpaRepository<Inventory, Long> {

    Optional<Inventory> findByWarehouseAndProduct(Warehouse warehouse, Product product);

    Optional<Inventory> findByWarehouse_IdAndProduct_Id(Long warehouseId, Long productId);

    Optional<Inventory> findByWarehouse_CodeIgnoreCaseAndProduct_SkuIgnoreCase(String warehouseCode, String productSku);

    List<Inventory> findByWarehouse_Id(Long warehouseId);

    List<Inventory> findByProduct_Id(Long productId);

    /**
     * S4-03 AC4: Khoá bi quan dòng tồn kho khi chốt đơn hàng, chống 2 người cùng chốt đơn dẫn đến âm tồn.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE i.warehouse.id = :warehouseId AND i.product.id = :productId")
    Optional<Inventory> findByWarehouseIdAndProductIdForUpdate(@Param("warehouseId") Long warehouseId,
                                                              @Param("productId") Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM Inventory i WHERE LOWER(i.warehouse.code) = LOWER(:warehouseCode) AND LOWER(i.product.sku) = LOWER(:productSku)")
    Optional<Inventory> findByWarehouseCodeAndProductSkuForUpdate(@Param("warehouseCode") String warehouseCode,
                                                                 @Param("productSku") String productSku);
}
