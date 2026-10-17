package com.erp.backend.repository;

import com.erp.backend.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/** S7-02: Kho dữ liệu phiếu thu / cấn trừ công nợ. */
public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, Long>, JpaSpecificationExecutor<PaymentReceipt> {

    /** S8-10 / S7-06: Phiếu thu theo trạng thái của 1 đại lý tới ngày. */
    java.util.List<PaymentReceipt> findByCustomer_IdAndStatusAndPaymentDateLessThanEqual(
            Long customerId, String status, java.time.LocalDate toDate);

    Optional<PaymentReceipt> findByCode(String code);

    boolean existsByCode(String code);

    @Query("select count(p) from PaymentReceipt p where p.customer.id = :customerId")
    long countByCustomerId(@Param("customerId") Long customerId);

    /** S7-05: Lấy danh sách phiếu thu phục vụ bảng đối chiếu theo nhân viên và khoảng ngày. */
    @Query("select p from PaymentReceipt p where (:collectorId is null or p.collectorId = :collectorId) "
            + "and p.paymentDate between :fromDate and :toDate order by p.paymentDate desc, p.createdAt desc, p.id desc")
    java.util.List<PaymentReceipt> findForReconciliation(
            @Param("collectorId") Long collectorId,
            @Param("fromDate") java.time.LocalDate fromDate,
            @Param("toDate") java.time.LocalDate toDate);
}
