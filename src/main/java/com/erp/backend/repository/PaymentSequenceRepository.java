package com.erp.backend.repository;

import com.erp.backend.entity.PaymentSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** S7-02: Bộ đếm số phiếu thu theo năm. */
public interface PaymentSequenceRepository extends JpaRepository<PaymentSequence, Integer> {

    @Modifying
    @Query(value = "insert into payment_sequences (seq_year, last_number) values (:year, 0) "
            + "on conflict (seq_year) do nothing", nativeQuery = true)
    int ensureYear(@Param("year") int year);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSequence s where s.seqYear = :year")
    Optional<PaymentSequence> findForUpdate(@Param("year") Integer year);
}
