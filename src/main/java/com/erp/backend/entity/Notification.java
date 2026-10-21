package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * S7-08: Thông báo trong ứng dụng (In-app notification / Chuông thông báo).
 * Dùng để thông báo nhắc hạn thanh toán trước 3 ngày, nhắc nợ quá hạn cho Đại lý,
 * và các cảnh báo hệ thống khác.
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notification_user", columnList = "user_id"),
        @Index(name = "idx_notification_customer", columnList = "customer_id"),
        @Index(name = "idx_notification_created", columnList = "created_at"),
        @Index(name = "idx_notification_unread", columnList = "user_id, is_read")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Notification {

    public static final String TYPE_PAYMENT_DUE_SOON = "PAYMENT_DUE_SOON";
    public static final String TYPE_PAYMENT_OVERDUE = "PAYMENT_OVERDUE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "customer_code", length = 30)
    private String customerCode;

    @Column(nullable = false, length = 250)
    private String title;

    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "notification_type", nullable = false, length = 50)
    private String notificationType;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(name = "reference_code", length = 50)
    private String referenceCode;

    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private boolean isRead = false;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
