package com.erp.backend.repository;

import com.erp.backend.entity.InvoiceSequence;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** S7-01: Bộ đếm số hoá đơn theo năm. */
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, Integer> {

    /** Tạo dòng bộ đếm của năm nếu chưa có (2 người phát hành cùng lúc không bị lỗi trùng khoá). PostgreSQL. */
    @Modifying
    @Query(value = "insert into invoice_sequences (seq_year, last_number) values (:year, 0) "
            + "on conflict (seq_year) do nothing", nativeQuery = true)
    int ensureYear(@Param("year") int year);

    /** Khoá dòng bộ đếm (SELECT ... FOR UPDATE): người sau chờ người trước lưu xong mới cấp số tiếp. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from InvoiceSequence s where s.seqYear = :year")
    Optional<InvoiceSequence> findForUpdate(@Param("year") Integer year);
}
