package com.erp.backend.repository;

import com.erp.backend.entity.PaymentReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

/** S7-08: Kho dữ liệu nhật ký gửi nhắc hạn thanh toán (chống gửi trùng). */
public interface PaymentReminderLogRepository extends JpaRepository<PaymentReminderLog, Long> {

    /**
     * Tiêu chí: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày.
     */
    boolean existsByInvoiceIdAndReminderDate(Long invoiceId, LocalDate reminderDate);

    List<PaymentReminderLog> findByInvoiceIdOrderBySentAtDesc(Long invoiceId);

    List<PaymentReminderLog> findByReminderDateOrderBySentAtDesc(LocalDate reminderDate);

    List<PaymentReminderLog> findByCustomerIdOrderBySentAtDesc(Long customerId);
}
