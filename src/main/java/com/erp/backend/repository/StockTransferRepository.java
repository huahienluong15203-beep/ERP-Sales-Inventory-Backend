package com.erp.backend.repository;

import com.erp.backend.entity.StockTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long>, JpaSpecificationExecutor<StockTransfer> {

    Optional<StockTransfer> findByCodeIgnoreCase(String code);

    /** Khoá dòng phiếu khi xuất / nhận: 2 người bấm cùng lúc thì người sau phải chờ và thấy trạng thái mới. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM StockTransfer t WHERE t.id = :id")
    Optional<StockTransfer> findByIdForUpdate(@Param("id") Long id);

    boolean existsByCodeIgnoreCase(String code);

    @Query("SELECT t.code FROM StockTransfer t WHERE t.code LIKE CONCAT(:prefix, '%') ORDER BY t.code DESC")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);

    List<StockTransfer> findBySourceWarehouse_Id(Long sourceWarehouseId);

    List<StockTransfer> findByDestWarehouse_Id(Long destWarehouseId);

    boolean existsBySourceWarehouse_IdOrDestWarehouse_Id(Long sourceWarehouseId, Long destWarehouseId);
}
