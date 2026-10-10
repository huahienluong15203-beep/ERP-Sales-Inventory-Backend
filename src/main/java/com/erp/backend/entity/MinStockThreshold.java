package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S5-09: Khai báo định mức tồn tối thiểu theo SKU và theo kho.
 * Phục vụ cảnh báo đứt hàng trên Sổ tồn kho và Dashboard kho.
 */
@Entity
@Table(name = "min_stock_thresholds", indexes = {
        @Index(name = "idx_min_stock_wh", columnList = "warehouse_id"),
        @Index(name = "idx_min_stock_product", columnList = "product_id")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_min_stock_wh_product", columnNames = {"warehouse_id", "product_id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class MinStockThreshold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Ngưỡng tồn tối thiểu khai báo (theo ĐVT cơ sở của SKU)
     */
    @Column(name = "min_threshold", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal minThreshold = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamp default now()")
    private LocalDateTime updatedAt;
}
