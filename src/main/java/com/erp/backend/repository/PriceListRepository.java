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

    java.util.List<PriceList> findByStatusOrderByStartDateDesc(String status);

    java.util.List<PriceList> findByCustomerGroupAndStatusOrderByStartDateDesc(
            com.erp.backend.entity.CustomerGroup customerGroup, String status);

    @Query("SELECT p FROM PriceList p WHERE p.status = 'ACTIVE' "
            + "AND p.startDate <= :date AND (p.endDate IS NULL OR p.endDate >= :date) "
            + "ORDER BY p.startDate DESC, p.version DESC, p.id DESC")
    java.util.List<PriceList> findAllEffective(@Param("date") java.time.LocalDate date);

    /** Khoá dòng bảng giá khi sửa để 2 người sửa cùng lúc không ghi đè nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PriceList p WHERE p.id = :id")
    Optional<PriceList> findByIdForUpdate(@Param("id") Long id);
}
