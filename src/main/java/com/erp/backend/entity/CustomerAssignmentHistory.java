package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * S3-06: Lịch sử phân công nhân viên phụ trách đại lý.
 * Chỉ thêm mới, không có API sửa hoặc xoá.
 */
@Entity
@Table(name = "customer_assignment_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CustomerAssignmentHistory {

    /** CREATE: gán khi tạo đại lý | ASSIGN: đổi người phụ trách | TRANSFER: chuyển giao hàng loạt */
    public static final String TYPE_CREATE = "CREATE";
    public static final String TYPE_ASSIGN = "ASSIGN";
    public static final String TYPE_TRANSFER = "TRANSFER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_sales_rep_id", updatable = false)
    private User fromSalesRep;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_sales_rep_id", updatable = false)
    private User toSalesRep;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by", updatable = false)
    private User changedBy;

    @Column(name = "change_type", nullable = false, length = 20, updatable = false)
    private String changeType;

    @Column(length = 500, updatable = false)
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", updatable = false)
    private LocalDateTime changedAt;
}
