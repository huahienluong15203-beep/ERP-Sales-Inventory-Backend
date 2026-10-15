package com.erp.backend.repository;

import com.erp.backend.entity.InvoiceLine;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;

/** S7-01: Dòng hoá đơn. */
public interface InvoiceLineRepository extends JpaRepository<InvoiceLine, Long> {

    /** Có dòng đơn nào trong danh sách đã được lập hoá đơn chưa. */
    boolean existsByOrderLineIdIn(Collection<Long> orderLineIds);
}
