package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Thực thể Đơn vị tính quy đổi của sản phẩm (S2-07).
 * Phục vụ Nhân viên kho và Quản lý kho:
 * - Mỗi SKU khai báo được nhiều đơn vị quy đổi (lon, lốc, thùng, két, khay...)
 * - Kèm hệ số quy đổi về đơn vị cơ sở (base_unit).
 * - Ví dụ: base_unit = "Lon" -> "Lốc" (factor = 6), "Thùng" (factor = 24).
 */
@Entity
@Table(name = "product_unit_conversions", indexes = {
        @Index(name = "idx_puc_product_id", columnList = "product_id"),
        @Index(name = "idx_puc_unit_name", columnList = "unit_name"),
        @Index(name = "idx_puc_barcode", columnList = "barcode"),
        @Index(name = "idx_puc_status", columnList = "status")
}, uniqueConstraints = {
        @UniqueConstraint(name = "uk_product_unit_name", columnNames = {"product_id", "unit_name"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ProductUnitConversion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Sản phẩm trực thuộc
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    // Tên đơn vị quy đổi (vd: Lốc, Thùng, Két, Khay...)
    @Column(name = "unit_name", nullable = false, length = 50)
    private String unitName;

    // Hệ số quy đổi về đơn vị cơ sở (phải > 0). Ví dụ: 1 Thùng = 24 Lon -> conversion_factor = 24.0000
    @Column(name = "conversion_factor", nullable = false, precision = 12, scale = 4)
    private BigDecimal conversionFactor;

    // Mã vạch riêng của quy cách đóng gói này (Barcode thùng / lốc nếu có)
    @Column(length = 50)
    private String barcode;

    // Đơn vị mặc định khi lập phiếu nhập kho (Gợi ý cho nhân viên kho)
    @Column(name = "is_default_purchase", nullable = false)
    @Builder.Default
    private Boolean isDefaultPurchase = false;

    // Đơn vị mặc định khi lập phiếu xuất / đơn bán hàng (Gợi ý cho kinh doanh)
    @Column(name = "is_default_sale", nullable = false)
    @Builder.Default
    private Boolean isDefaultSale = false;

    // Ghi chú hoặc quy cách chi tiết (vd: "Thùng carton 24 lon 330ml")
    @Column(length = 255)
    private String description;

    // Trạng thái áp dụng: ACTIVE (Đang áp dụng), INACTIVE (Ngừng áp dụng)
    @Column(length = 20, nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
