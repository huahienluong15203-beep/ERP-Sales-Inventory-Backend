package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S3-02: Lịch sử thay đổi giá của sản phẩm trong bảng giá.
 * Ghi tự động mỗi khi thêm / đổi / xoá dòng giá. Bản ghi không sửa, không xoá được (@Immutable, không có API sửa/xoá).
 */
@Entity
@Immutable
@Table(name = "price_history", indexes = {
        @Index(name = "idx_price_history_sku", columnList = "product_sku"),
        @Index(name = "idx_price_history_list", columnList = "price_list_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PriceHistory {

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false, updatable = false)
    private PriceList priceList;

    @Column(name = "price_list_code", nullable = false, length = 40, updatable = false)
    private String priceListCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", nullable = false, length = 30, updatable = false)
    private CustomerGroup customerGroup;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50, updatable = false)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    // CREATE (thêm giá) | UPDATE (đổi giá) | DELETE (bỏ giá)
    @Column(name = "change_type", nullable = false, length = 20, updatable = false)
    private String changeType;

    @Column(name = "old_price", precision = 15, scale = 2, updatable = false)
    private BigDecimal oldPrice;

    @Column(name = "new_price", precision = 15, scale = 2, updatable = false)
    private BigDecimal newPrice;

    @Column(name = "old_floor_price", precision = 15, scale = 2, updatable = false)
    private BigDecimal oldFloorPrice;

    @Column(name = "new_floor_price", precision = 15, scale = 2, updatable = false)
    private BigDecimal newFloorPrice;

    // Ngày bắt đầu áp dụng giá (ngày bắt đầu hiệu lực của bảng giá lúc thay đổi)
    @Column(name = "effective_date", nullable = false, updatable = false)
    private LocalDate effectiveDate;

    @Column(name = "changed_by_id", updatable = false)
    private Long changedById;

    @Column(name = "changed_by_username", length = 50, updatable = false)
    private String changedByUsername;

    @Column(name = "changed_by_name", length = 100, updatable = false)
    private String changedByName;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
