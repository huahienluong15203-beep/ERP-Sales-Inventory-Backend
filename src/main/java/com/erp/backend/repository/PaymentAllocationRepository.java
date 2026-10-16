package com.erp.backend.repository;

import com.erp.backend.entity.PaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/** S7-02: Bút toán phân bổ cấn trừ thanh toán vào hoá đơn. */
public interface PaymentAllocationRepository extends JpaRepository<PaymentAllocation, Long> {

    List<PaymentAllocation> findByPaymentReceiptId(Long paymentReceiptId);

    List<PaymentAllocation> findByInvoiceId(Long invoiceId);

    @Query("select coalesce(sum(a.allocatedAmount), 0) from PaymentAllocation a where a.invoice.id = :invoiceId")
    BigDecimal sumAllocatedByInvoiceId(@Param("invoiceId") Long invoiceId);
}
