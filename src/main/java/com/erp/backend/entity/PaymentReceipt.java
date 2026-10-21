package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S7-02: Phiếu thu ghi nhận thanh toán và cấn trừ công nợ đại lý.
 * - Ghi số tiền, hình thức thu, ngày thu, người thu, ghi chú.
 * - Đối trừ tự động (FIFO: hoá đơn cũ nhất trước) hoặc đối trừ hoá đơn chỉ định.
 * - Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
 */
@Entity
@Table(name = "payment_receipts", uniqueConstraints = {
        @UniqueConstraint(name = "uk_payment_receipt_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_payment_customer", columnList = "customer_id"),
        @Index(name = "idx_payment_date", columnList = "payment_date"),
        @Index(name = "idx_payment_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PaymentReceipt {

    public static final String STATUS_PENDING_SUBMISSION = "PENDING_SUBMISSION";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_VOIDED = "VOIDED";

    public static final String METHOD_CASH = "CASH";
    public static final String METHOD_BANK_TRANSFER = "BANK_TRANSFER";
    public static final String METHOD_OTHER = "OTHER";

    public static final String ALLOCATION_FIFO = "FIFO";
    public static final String ALLOCATION_SPECIFIC = "SPECIFIC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "customer_code", nullable = false, length = 30, updatable = false)
    private String customerCode;

    @Column(name = "customer_name", nullable = false, length = 200, updatable = false)
    private String customerName;

    @Column(name = "customer_address", length = 500, updatable = false)
    private String customerAddress;

    @Column(name = "customer_phone", length = 30, updatable = false)
    private String customerPhone;

    /** Tên người nộp tiền / tài xế bàn giao tiền (nếu để trống mặc định là tên đại lý). */
    @Column(name = "payer_name", length = 150)
    private String payerName;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "payment_method", nullable = false, length = 30, updatable = false)
    private String paymentMethod;

    @Column(name = "payment_date", nullable = false, updatable = false)
    private LocalDate paymentDate;

    @Column(name = "collector_name", nullable = false, length = 100, updatable = false)
    private String collectorName;

    @Column(name = "collector_username", length = 50, updatable = false)
    private String collectorUsername;

    @Column(name = "collector_id", updatable = false)
    private Long collectorId;

    @Column(length = 500)
    private String note;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_CONFIRMED;

    @Column(name = "allocation_type", nullable = false, length = 30, updatable = false)
    @Builder.Default
    private String allocationType = ALLOCATION_FIFO;

    @Column(name = "receipt_image_url", length = 1000)
    private String receiptImageUrl;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by_username", length = 50)
    private String confirmedByUsername;

    @Column(name = "confirmed_by_id")
    private Long confirmedById;

    @Column(name = "rejected_at")
    private LocalDateTime rejectedAt;

    @Column(name = "rejected_by_username", length = 50)
    private String rejectedByUsername;

    @Column(name = "rejected_by_id")
    private Long rejectedById;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "adjusted_at")
    private LocalDateTime adjustedAt;

    @Column(name = "adjusted_by_username", length = 50)
    private String adjustedByUsername;

    @Column(name = "adjusted_by_id")
    private Long adjustedById;

    @Column(name = "adjustment_reason", length = 500)
    private String adjustmentReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_username", length = 50, updatable = false)
    private String createdByUsername;

    @OneToMany(mappedBy = "paymentReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PaymentAllocation> allocations = new ArrayList<>();

    public void addAllocation(PaymentAllocation allocation) {
        allocation.setPaymentReceipt(this);
        allocations.add(allocation);
    }

    /**
     * Tiêu chí AC5: Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
     */
    @PreRemove
    public void preventDelete() {
        throw new IllegalStateException("Phiếu thu đã ghi nhận không được xoá, chỉ lập bút toán điều chỉnh");
    }
}
