package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S4-03 / SCRUM-154: Tồn kho sản phẩm theo từng kho hàng.
 * - physicalStock: Tồn thực tế trong kho (theo ĐVT cơ sở).
 * - reservedStock: Tồn đang giữ chỗ cho các đơn hàng đã chốt (PENDING_APPROVAL / APPROVED chưa xuất kho).
 * - availableStock: Tồn khả dụng = physicalStock - reservedStock.
 */
@Entity
@Table(name = "inventories", uniqueConstraints = {
        @UniqueConstraint(name = "uk_inventory_warehouse_product", columnNames = {"warehouse_id", "product_id"})
}, indexes = {
        @Index(name = "idx_inventory_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_inventory_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /** Tồn thực tế trong kho (theo ĐVT cơ sở) */
    @Column(name = "physical_stock", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal physicalStock = BigDecimal.ZERO;

    /** Tồn đang giữ chỗ cho các đơn hàng chưa xuất kho (theo ĐVT cơ sở) */
    @Column(name = "reserved_stock", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal reservedStock = BigDecimal.ZERO;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /**
     * Tồn khả dụng = Tồn thực tế - Tồn đang giữ chỗ cho đơn khác.
     */
    public BigDecimal getAvailableStock() {
        BigDecimal physical = physicalStock != null ? physicalStock : BigDecimal.ZERO;
        BigDecimal reserved = reservedStock != null ? reservedStock : BigDecimal.ZERO;
        return physical.subtract(reserved);
    }
}
