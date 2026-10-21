package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.time.LocalDateTime;

/**
 * S4-05: Lịch sử chốt / duyệt đơn hàng.
 * Chỉ thêm mới: entity @Immutable, mọi cột updatable = false, repository không có hàm sửa / xoá, không có API sửa / xoá.
 */
@Entity
@Immutable
@Table(name = "sales_order_approvals", indexes = @Index(name = "idx_order_approvals_order", columnList = "order_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SalesOrderApproval {

    /** NV kinh doanh gửi đơn cần duyệt */
    public static final String ACTION_SUBMIT = "SUBMIT";
    /** Đơn không vi phạm hạn mức / giá sàn: tự duyệt khi chốt */
    public static final String ACTION_AUTO_APPROVE = "AUTO_APPROVE";
    public static final String ACTION_APPROVE = "APPROVE";
    public static final String ACTION_REJECT = "REJECT";
    public static final String ACTION_RETURN = "RETURN";
    public static final String ACTION_CANCEL = "CANCEL";
    public static final String ACTION_CHANGE_STATUS = "CHANGE_STATUS";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false, updatable = false)
    private SalesOrder order;

    @Column(nullable = false, length = 20, updatable = false)
    private String action;

    @Column(name = "from_status", length = 20, updatable = false)
    private String fromStatus;

    @Column(name = "to_status", nullable = false, length = 20, updatable = false)
    private String toStatus;

    /** Ý kiến người duyệt (bắt buộc khi Từ chối / Trả lại sửa) hoặc lý do cần duyệt khi gửi */
    @Column(length = 1000, updatable = false)
    private String comment;

    @Column(name = "actor_id", updatable = false)
    private Long actorId;

    @Column(name = "actor_username", length = 50, updatable = false)
    private String actorUsername;

    @Column(name = "actor_full_name", length = 100, updatable = false)
    private String actorFullName;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
