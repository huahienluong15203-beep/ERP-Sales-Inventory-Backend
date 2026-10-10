package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S5-04 & S5-07: Lưu thông tin Lô hàng & Hạn sử dụng theo kho.
 */
@Entity
@Table(name = "product_lots", indexes = {
        @Index(name = "idx_lot_product_wh", columnList = "product_id, warehouse_id"),
        @Index(name = "idx_lot_batch", columnList = "batch_number"),
        @Index(name = "idx_lot_expired_date", columnList = "expired_date")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_lot_product_wh_batch", columnNames = {"product_id", "warehouse_id", "batch_number"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ProductLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    @Column(name = "expired_date")
    private LocalDate expiredDate;

    @Column(nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamp default now()")
    private LocalDateTime updatedAt;
}
