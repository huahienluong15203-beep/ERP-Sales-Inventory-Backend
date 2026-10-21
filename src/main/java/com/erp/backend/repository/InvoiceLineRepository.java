package com.erp.backend.repository;

import com.erp.backend.entity.InvoiceLine;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

/** S7-01: Dòng hoá đơn. */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {

    /** Có dòng đơn nào trong danh sách đã được lập hoá đơn chưa. */
    boolean existsByOrderLineIdIn(Collection<Long> orderLineIds);

    java.util.List<InvoiceLine> findByInvoice_IdOrderByLineNoAsc(Long invoiceId);

    /** S8-01: Khoá dòng hoá đơn khi lập phiếu trả để 2 người trả cùng lúc không vượt số đã giao. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from InvoiceLine l where l.id = :id")
    Optional<InvoiceLine> findByIdForUpdate(@Param("id") Long id);
}
