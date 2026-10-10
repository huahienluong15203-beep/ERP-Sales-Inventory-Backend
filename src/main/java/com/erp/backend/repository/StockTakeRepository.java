package com.erp.backend.repository;

import com.erp.backend.entity.StockTake;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StockTakeRepository extends JpaRepository<StockTake, Long>, JpaSpecificationExecutor<StockTake> {

    boolean existsByCode(String code);

    /** S5-08: Không cho 2 phiếu nháp trùng kho + nhóm hàng cùng lúc. */
    boolean existsByWarehouse_IdAndCategory_IdAndStatus(Long warehouseId, Long categoryId, String status);

    boolean existsByWarehouse_IdAndCategoryIsNullAndStatus(Long warehouseId, String status);

    /** Khoá phiếu khi nhập số đếm / chốt để 2 người thao tác không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StockTake s where s.id = :id")
    Optional<StockTake> findByIdForUpdate(@Param("id") Long id);
}
