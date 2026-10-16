package com.erp.backend.repository;

import com.erp.backend.entity.StockAdjustment;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * S8-03: Repository quản lý phiếu điều chỉnh tồn kho có phê duyệt.
 */
@Repository
public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, Long>, JpaSpecificationExecutor<StockAdjustment> {

    Optional<StockAdjustment> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("SELECT a.code FROM StockAdjustment a WHERE a.code LIKE CONCAT(:prefix, '%') ORDER BY a.code DESC")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM StockAdjustment a WHERE a.id = :id")
    Optional<StockAdjustment> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a FROM StockAdjustment a WHERE " +
            "(:warehouseId IS NULL OR a.warehouse.id = :warehouseId) AND " +
            "(:status IS NULL OR a.status = :status) AND " +
            "(:reason IS NULL OR a.adjustmentReason = :reason) AND " +
            "(:fromDate IS NULL OR a.adjustmentDate >= :fromDate) AND " +
            "(:toDate IS NULL OR a.adjustmentDate <= :toDate) AND " +
            "(:keyword IS NULL OR LOWER(a.code) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(a.warehouseName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(a.createdByName) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<StockAdjustment> searchAdjustments(
            @Param("warehouseId") Long warehouseId,
            @Param("status") String status,
            @Param("reason") String reason,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("keyword") String keyword,
            Pageable pageable);
}
