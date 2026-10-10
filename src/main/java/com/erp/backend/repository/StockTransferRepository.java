package com.erp.backend.repository;

import com.erp.backend.entity.StockTransfer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long>, JpaSpecificationExecutor<StockTransfer> {

    Optional<StockTransfer> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("SELECT t.code FROM StockTransfer t WHERE t.code LIKE CONCAT(:prefix, '%') ORDER BY t.code DESC")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);

    List<StockTransfer> findBySourceWarehouse_Id(Long sourceWarehouseId);

    List<StockTransfer> findByDestWarehouse_Id(Long destWarehouseId);
}
