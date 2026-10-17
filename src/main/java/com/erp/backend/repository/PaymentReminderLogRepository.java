package com.erp.backend.repository;

import com.erp.backend.entity.PaymentReminderLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * S7-08: Khoá tư vấn (advisory lock) PostgreSQL theo transaction: chỉ 1 lượt quét nhắc hạn chạy tại 1 thời điểm
     * (cronjob và nút chạy tay bấm cùng lúc). Trả false nếu lượt khác đang giữ khoá; tự nhả khi transaction kết thúc.
     */
    @Query(value = "select pg_try_advisory_xact_lock(7080817)", nativeQuery = true)
    boolean tryLockReminderRun();
}
