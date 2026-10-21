package com.erp.backend.repository;

import com.erp.backend.entity.CreditNote;
import com.erp.backend.entity.SalesReturn;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** S8-02: Chứng từ giảm trừ công nợ (chỉ thêm mới, không sửa / xoá). */
public interface CreditNoteRepository extends JpaRepository<CreditNote, Long>, JpaSpecificationExecutor<CreditNote> {

    boolean existsBySalesReturn_Id(Long salesReturnId);

    /** S8-10 / S7-06: Chứng từ giảm trừ của 1 đại lý tới ngày. */
    List<CreditNote> findByCustomer_IdAndNoteDateLessThanEqual(Long customerId, java.time.LocalDate toDate);

    Optional<CreditNote> findBySalesReturn_Id(Long salesReturnId);

    List<CreditNote> findBySalesReturn_IdIn(Collection<Long> salesReturnIds);

    /** Tổng tiền và số lượng đã giảm trừ trên 1 dòng hoá đơn qua các phiếu trả trước (để không giảm trừ vượt dòng gốc). */
    @Query("select coalesce(sum(l.amount), 0) from CreditNoteLine l where l.invoiceLineId = :invoiceLineId")
    BigDecimal sumCreditedAmountByInvoiceLine(@Param("invoiceLineId") Long invoiceLineId);

    @Query("select coalesce(sum(l.returnQuantity), 0) from CreditNoteLine l where l.invoiceLineId = :invoiceLineId")
    BigDecimal sumCreditedQuantityByInvoiceLine(@Param("invoiceLineId") Long invoiceLineId);

    /** Số dư có (phần giảm trừ chưa đối trừ được) của đại lý, trừ vào công nợ. */
    @Query("select coalesce(sum(c.unappliedAmount), 0) from CreditNote c where c.customer.id = :customerId")
    BigDecimal sumUnappliedByCustomer(@Param("customerId") Long customerId);

    @Query("select c.customer.id, coalesce(sum(c.unappliedAmount), 0) from CreditNote c "
            + "where c.customer.id in :customerIds and c.unappliedAmount > 0 group by c.customer.id")
    List<Object[]> sumUnappliedByCustomerIds(@Param("customerIds") Collection<Long> customerIds);

    /** Phiếu trả hàng đã nhập kho nhưng chưa lập chứng từ giảm trừ (cũ nhất trước). */
    @Query("select r from SalesReturn r where r.status = 'RECEIVED' and not exists "
            + "(select c.id from CreditNote c where c.salesReturn = r) order by r.receivedAt asc, r.id asc")
    List<SalesReturn> findReceivedReturnsWithoutCreditNote();
}
