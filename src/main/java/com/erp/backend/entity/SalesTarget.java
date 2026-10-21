package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S8-04: Chỉ tiêu doanh số theo tháng cho 1 NV kinh doanh (REP) hoặc 1 khu vực (REGION).
 * - periodMonth: ngày đầu tháng (vd 2026-10-01).
 * - targetKey = "REP:<userId>" hoặc "REGION:<regionId>": duy nhất cùng periodMonth (1 đối tượng 1 chỉ tiêu / tháng).
 * - Kỳ đã qua bị khoá (không thêm / sửa / xoá); sửa phải có lý do và ghi nhật ký trước / sau.
 */
@Entity
@Table(name = "sales_targets", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sales_target_period_key", columnNames = {"period_month", "target_key"})
}, indexes = {
        @Index(name = "idx_sales_target_period", columnList = "period_month")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SalesTarget {

    public static final String TYPE_REP = "REP";
    public static final String TYPE_REGION = "REGION";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "period_month", nullable = false, updatable = false)
    private LocalDate periodMonth;

    @Column(name = "target_type", nullable = false, length = 10, updatable = false)
    private String targetType;

    @Column(name = "target_key", nullable = false, length = 40, updatable = false)
    private String targetKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sales_rep_id", updatable = false)
    private User salesRep;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "region_id", updatable = false)
    private Region region;

    @Column(name = "target_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal targetAmount;

    @Column(length = 500)
    private String note;

    @Version
    private Long version;

    @Column(name = "created_by_username", length = 50, updatable = false)
    private String createdByUsername;

    @Column(name = "updated_by_username", length = 50)
    private String updatedByUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public static String keyOf(String type, Long id) {
        return type + ":" + id;
    }
}
