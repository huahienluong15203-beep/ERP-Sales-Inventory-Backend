package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S7-08: Nhật ký gửi nhắc hạn thanh toán hoá đơn cho Đại lý.
 * Đảm bảo tiêu chí: Không gửi trùng nhiều lần cho cùng một hoá đơn trong cùng một ngày.
 */
@Entity
@Table(name = "payment_reminder_logs", uniqueConstraints = {
        @UniqueConstraint(name = "uk_reminder_invoice_date", columnNames = {"invoice_id", "reminder_date"})
}, indexes = {
        @Index(name = "idx_reminder_invoice_date", columnList = "invoice_id, reminder_date"),
        @Index(name = "idx_reminder_customer", columnList = "customer_id"),
        @Index(name = "idx_reminder_date", columnList = "reminder_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PaymentReminderLog {

    public static final String REMINDER_BEFORE_DUE_3_DAYS = "BEFORE_DUE_3_DAYS";
    public static final String REMINDER_OVERDUE = "OVERDUE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "invoice_code", nullable = false, length = 30)
    private String invoiceCode;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "customer_code", nullable = false, length = 30)
    private String customerCode;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "reminder_type", nullable = false, length = 30)
    private String reminderType;

    @Column(name = "reminder_date", nullable = false)
    private LocalDate reminderDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "days_difference", nullable = false)
    private Long daysDifference;

    @Column(name = "email_sent", nullable = false)
    private boolean emailSent;

    @Column(name = "in_app_sent", nullable = false)
    private boolean inAppSent;

    @Column(name = "recipient_email", length = 100)
    private String recipientEmail;

    @Column(name = "sent_at", nullable = false)
    private LocalDateTime sentAt;
}
