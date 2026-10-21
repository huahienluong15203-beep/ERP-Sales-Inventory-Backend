package com.erp.backend.repository;

import com.erp.backend.entity.SalesReturn;
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
 * S8-01: Repository quản lý phiếu trả hàng từ đại lý.
 */
@Repository
public interface SalesReturnRepository extends JpaRepository<SalesReturn, Long>, JpaSpecificationExecutor<SalesReturn> {

    Optional<SalesReturn> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("SELECT r.code FROM SalesReturn r WHERE r.code LIKE CONCAT(:prefix, '%') ORDER BY r.code DESC")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SalesReturn r WHERE r.id = :id")
    Optional<SalesReturn> findByIdForUpdate(@Param("id") Long id);

    List<SalesReturn> findByInvoice_IdOrderByCreatedAtDesc(Long invoiceId);

    List<SalesReturn> findByCustomer_IdOrderByCreatedAtDesc(Long customerId);

    @Query("SELECT r FROM SalesReturn r WHERE " +
            "(:customerId IS NULL OR r.customer.id = :customerId) AND " +
            "(:warehouseId IS NULL OR r.receivingWarehouse.id = :warehouseId) AND " +
            "(:status IS NULL OR r.status = :status) AND " +
            "(:fromDate IS NULL OR r.returnDate >= :fromDate) AND " +
            "(:toDate IS NULL OR r.returnDate <= :toDate) AND " +
            "(:keyword IS NULL OR LOWER(r.code) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(r.invoiceCode) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(r.customerName) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<SalesReturn> searchReturns(
            @Param("customerId") Long customerId,
            @Param("warehouseId") Long warehouseId,
            @Param("status") String status,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("keyword") String keyword,
            Pageable pageable);
}
