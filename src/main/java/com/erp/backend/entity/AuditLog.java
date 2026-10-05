package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * S2-04: Nhật ký thao tác hệ thống (Audit Log).
 * - Ghi lại mọi thao tác trên tồn kho, giá, hạn mức công nợ và hoá đơn.
 * - Mỗi bản ghi lưu người thực hiện, thời điểm, giá trị trước và sau, lý do thay đổi.
 * - Cho phép lọc theo người dùng, loại đối tượng, khoảng thời gian.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_module_created", columnList = "module, created_at DESC"),
        @Index(name = "idx_audit_logs_target", columnList = "target_type, target_id"),
        @Index(name = "idx_audit_logs_actor", columnList = "actor_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Phân hệ: INVENTORY, PRICING, DEBT_LIMIT, INVOICE, CUSTOMER
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuditModule module;

    // Tên hành động: UPDATE_DEBT_LIMIT, LOCK_TRANSACTION, UNLOCK_TRANSACTION, ADJUST_STOCK, UPDATE_PRICE, CREATE_INVOICE, HTTP_MUTATION, v.v.
    @Column(nullable = false, length = 50)
    private String action;

    // Loại đối tượng: CUSTOMER, PRODUCT, INVENTORY, INVOICE, v.v.
    @Column(name = "target_type", length = 50)
    private String targetType;

    // ID đối tượng bị tác động (nếu có)
    @Column(name = "target_id")
    private Long targetId;

    // Mã đối tượng (vd mã đại lý DL-001, mã SKU, mã hóa đơn...)
    @Column(name = "target_code", length = 50)
    private String targetCode;

    // Người thực hiện
    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_username", length = 50)
    private String actorUsername;

    @Column(name = "actor_full_name", length = 100)
    private String actorFullName;

    // S2-03 & S2-04: Ảnh đại diện người thực hiện
    @Column(name = "actor_avatar_url", length = 500)
    private String actorAvatarUrl;

    // Giá trị trước thay đổi (chuỗi hoặc JSON)
    @Column(name = "old_value", columnDefinition = "TEXT")
    private String oldValue;

    // Giá trị sau thay đổi (chuỗi hoặc JSON)
    @Column(name = "new_value", columnDefinition = "TEXT")
    private String newValue;

    // Lý do thay đổi (bắt buộc đối với hạn mức, khóa đại lý, điều chỉnh kho)
    @Column(length = 500)
    private String reason;

    // IP của người gọi
    @Column(name = "ip_address", length = 50)
    private String ipAddress;

    // User-Agent (trình duyệt / nền tảng gọi API)
    @Column(name = "user_agent", length = 300)
    private String userAgent;

    // Phương thức HTTP (POST, PUT, PATCH, DELETE)
    @Column(name = "http_method", length = 10)
    private String httpMethod;

    // Đường dẫn URI gọi đến
    @Column(name = "request_uri", length = 255)
    private String requestUri;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
