package com.erp.backend.repository;

import com.erp.backend.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** S7-02: Kho dữ liệu phiếu thu / cấn trừ công nợ. */
public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long>, JpaSpecificationExecutor<PaymentReceipt> {

    Optional<PaymentReceipt> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select count(p) from PaymentReceipt p where p.customer.id = :customerId")
    long countByCustomerId(@Param("customerId") Long customerId);
}
