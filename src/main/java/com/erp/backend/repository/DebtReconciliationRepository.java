package com.erp.backend.repository;

import com.erp.backend.entity.DebtReconciliation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** S7-06: Biên bản đối chiếu công nợ (chỉ thêm mới, không xoá). */
public interface DebtReconciliationRepository extends JpaRepository<DebtReconciliation, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DebtReconciliation r where r.id = :id")
    Optional<DebtReconciliation> findByIdForUpdate(@Param("id") Long id);

    List<DebtReconciliation> findByCustomer_IdOrderByIdDesc(Long customerId);

    List<DebtReconciliation> findTop500ByOrderByIdDesc();

    boolean existsByCustomer_IdAndFromDateAndToDateAndStatusIn(Long customerId, LocalDate fromDate, LocalDate toDate,
                                                               Collection<String> statuses);

    @Query("select r.code from DebtReconciliation r where r.code like concat(:prefix, '%')")
    List<String> findCodesByPrefix(@Param("prefix") String prefix);
}
