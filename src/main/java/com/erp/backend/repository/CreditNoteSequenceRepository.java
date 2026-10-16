package com.erp.backend.repository;

import com.erp.backend.entity.CreditNoteSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** S8-02: Bộ đếm số chứng từ giảm trừ theo năm. */
public interface CreditNoteSequenceRepository extends JpaRepository<CreditNoteSequence, Integer> {

    @Modifying
    @Query(value = "insert into credit_note_sequences (seq_year, last_number) values (:year, 0) "
            + "on conflict (seq_year) do nothing", nativeQuery = true)
    int ensureYear(@Param("year") int year);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CreditNoteSequence s where s.seqYear = :year")
    Optional<CreditNoteSequence> findForUpdate(@Param("year") Integer year);
}
