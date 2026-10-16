package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S8-03: Phiếu điều chỉnh tồn kho có phê duyệt.
 * - Dùng cho hàng hỏng, hàng mất, hàng hết hạn phải huỷ.
 * - Bắt buộc chọn lý do và ghi rõ giá trị tổn thất.
 * - Chỉ Quản lý kho được duyệt; người lập và người duyệt không được là một.
 * - Mọi phiếu điều chỉnh đều xuất hiện trên thẻ kho.
 */
@Entity
@Table(name = "stock_adjustments", uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_adjustment_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_stock_adjustment_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_stock_adjustment_status", columnList = "status"),
        @Index(name = "idx_stock_adjustment_date", columnList = "adjustment_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockAdjustment {

    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL"; // Chờ Quản lý kho duyệt
    public static final String STATUS_APPROVED = "APPROVED";                 // Quản lý kho đã duyệt & trừ tồn
    public static final String STATUS_REJECTED = "REJECTED";                 // Quản lý kho từ chối
    public static final String STATUS_CANCELLED = "CANCELLED";               // Người lập huỷ phiếu

    // Lý do điều chỉnh bắt buộc theo tiêu chí bài toán
    public static final String REASON_DAMAGED_GOODS = "DAMAGED_GOODS";       // Hàng hỏng / hư hại
    public static final String REASON_LOST_GOODS = "LOST_GOODS";             // Hàng mất / thất thoát kho
    public static final String REASON_EXPIRED_DISPOSAL = "EXPIRED_DISPOSAL"; // Hàng hết hạn phải huỷ
    public static final String REASON_OTHER = "OTHER";                       // Lý do khác

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "adjustment_date", nullable = false)
    private LocalDate adjustmentDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "warehouse_code", nullable = false, length = 30)
    private String warehouseCode;

    @Column(name = "warehouse_name", nullable = false, length = 150)
    private String warehouseName;

    /** Bắt buộc chọn lý do: DAMAGED_GOODS, LOST_GOODS, EXPIRED_DISPOSAL, OTHER */
    @Column(name = "adjustment_reason", nullable = false, length = 50)
    private String adjustmentReason;

    /** Diễn giải lý do chi tiết */
    @Column(name = "reason_description", length = 500)
    private String reasonDescription;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_PENDING_APPROVAL;

    /** Tổng số lượng mặt hàng điều chỉnh giảm */
    @Column(name = "total_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    /** Bắt buộc ghi rõ giá trị tổn thất (tính theo giá vốn costPrice) */
    @Column(name = "total_loss_value", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalLossValue = BigDecimal.ZERO;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    /** ID của Quản lý kho phê duyệt (bắt buộc theo quy tắc kiểm toán ngầm) */
    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_by_name", length = 100)
    private String approvedByName;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "stockAdjustment", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StockAdjustmentLine> lines = new ArrayList<>();

    public void addLine(StockAdjustmentLine line) {
        lines.add(line);
        line.setStockAdjustment(this);
    }
}
