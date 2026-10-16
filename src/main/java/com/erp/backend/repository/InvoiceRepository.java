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

    /** S7-02: Lấy danh sách hoá đơn còn nợ của đại lý sắp xếp theo ngày cũ nhất trước (FIFO). */
    @Query("select i from Invoice i where i.customer.id = :customerId and (i.paymentStatus is null or i.paymentStatus <> 'PAID') and i.remainingAmount > 0 order by i.invoiceDate asc, i.id asc")
    java.util.List<Invoice> findUnpaidByCustomerIdOrderByInvoiceDateAsc(@Param("customerId") Long customerId);

    /** S7-02: Khoá dòng các hoá đơn để cập nhật cấn trừ công nợ. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Invoice i where i.id in :ids")
    java.util.List<Invoice> findAllByIdForUpdate(@Param("ids") java.util.Collection<Long> ids);

    /** S7-08: Lấy tất cả hoá đơn còn nợ cần quét nhắc hạn thanh toán (Eager fetch customer để tránh N+1). */
    @Query("select i from Invoice i join fetch i.customer c left join fetch c.user u left join fetch c.portalUser pu "
            + "where i.status = 'ISSUED' and (i.paymentStatus is null or i.paymentStatus <> 'PAID') and i.remainingAmount > 0")
    java.util.List<Invoice> findUnpaidIssuedInvoicesWithCustomer();
}
