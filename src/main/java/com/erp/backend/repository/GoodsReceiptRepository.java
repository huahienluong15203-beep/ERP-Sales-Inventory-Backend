package com.erp.backend.repository;

import com.erp.backend.entity.GoodsReceipt;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long>, JpaSpecificationExecutor<GoodsReceipt> {

    Optional<GoodsReceipt> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByDocumentNumberIgnoreCaseAndSupplier_Id(String documentNumber, Long supplierId);

    @Query("SELECT r.code FROM GoodsReceipt r WHERE r.code LIKE CONCAT(:prefix, '%') ORDER BY r.code DESC")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);

    List<GoodsReceipt> findByWarehouse_IdOrderByReceiptDateDesc(Long warehouseId);

    List<GoodsReceipt> findBySupplier_IdOrderByReceiptDateDesc(Long supplierId);

    /** Khoá dòng khi xác nhận để 2 người bấm Xác nhận cùng lúc không cộng tồn 2 lần. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM GoodsReceipt r WHERE r.id = :id")
    Optional<GoodsReceipt> findByIdForUpdate(@Param("id") Long id);

    boolean existsBySupplier_Id(Long supplierId);

    boolean existsByWarehouse_Id(Long warehouseId);
}
