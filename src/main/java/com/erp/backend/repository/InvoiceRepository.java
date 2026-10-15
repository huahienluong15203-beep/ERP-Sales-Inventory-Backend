package com.erp.backend.repository;

import com.erp.backend.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/** S7-01: Hoá đơn (chỉ thêm mới, không sửa / xoá). */
public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    /** Ngày của hoá đơn mới nhất trong năm (số hoá đơn phải tăng theo ngày). */
    @Query("select max(i.invoiceDate) from Invoice i where i.seqYear = :year")
    LocalDate maxInvoiceDate(@Param("year") Integer year);
}
