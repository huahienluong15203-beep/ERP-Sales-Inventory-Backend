package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S2-10: Một dòng giá trong bảng giá: giá bán và giá sàn của một sản phẩm.
 * Bán dưới giá sàn sẽ phải qua duyệt (xử lý ở phần đặt hàng).
 */
@Entity
@Table(name = "price_list_items",
        uniqueConstraints = @UniqueConstraint(name = "uk_price_list_item_product", columnNames = {"price_list_id", "product_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PriceListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false)
    private PriceList priceList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Lưu lại SKU và tên tại thời điểm định giá để hiển thị nhanh
    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    // Giá bán theo đơn vị tính cơ sở của sản phẩm (VND)
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal price;

    @Column(name = "floor_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal floorPrice;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
