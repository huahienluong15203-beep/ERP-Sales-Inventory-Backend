package com.erp.backend.repository;

import com.erp.backend.entity.PriceList;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface PriceListRepository extends JpaRepository<PriceList, Long>, JpaSpecificationExecutor<PriceList> {

    boolean existsByCodeIgnoreCase(String code);

    long countByStatus(String status);

    long countByCustomerGroup(com.erp.backend.entity.CustomerGroup customerGroup);

    long countByHasOrdersTrue();

    boolean existsByCodeIgnoreCaseAndIdNot(String code, Long id);

    /** Khoá dòng bảng giá khi sửa để 2 người sửa cùng lúc không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PriceList p WHERE p.id = :id")
    Optional<PriceList> findByIdForUpdate(@Param("id") Long id);
}
