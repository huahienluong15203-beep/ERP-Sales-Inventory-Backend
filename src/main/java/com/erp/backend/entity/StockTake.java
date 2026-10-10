package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S5-08: Phiếu kiểm kê kho. Lúc tạo hệ thống chụp số tồn sổ (systemQuantity) của từng mặt hàng;
 * nhập số đếm thực tế, tự tính chênh lệch; QL kho chốt (bắt buộc lý do) thì tồn được điều chỉnh về số thực đếm.
 * Phiếu đã chốt / đã huỷ không sửa, không xoá.
 */
@Entity
@Table(name = "stock_takes", indexes = {
        @Index(name = "idx_stock_takes_warehouse_status", columnList = "warehouse_id, status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockTake {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    /** Kiểm kê theo nhóm hàng (gồm nhóm con); null = cả kho */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_username", length = 50)
    private String createdByUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_by_username", length = 50)
    private String closedByUsername;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    /** Lý do chốt / huỷ (bắt buộc) */
    @Column(name = "close_reason", length = 500)
    private String closeReason;

    @OneToMany(mappedBy = "stockTake", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sku ASC")
    @Builder.Default
    private List<StockTakeLine> lines = new ArrayList<>();

    public void addLine(StockTakeLine line) {
        line.setStockTake(this);
        lines.add(line);
    }
}
