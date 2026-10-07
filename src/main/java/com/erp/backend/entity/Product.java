package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Thực thể Sản phẩm (Product) phục vụ EP-02 (S2-05, S2-08...).
 * Quản lý danh mục 5.000 mã hàng (SKU).
 */
@Entity
@Table(name = "products", indexes = {
        @Index(name = "idx_product_sku", columnList = "sku", unique = true),
        @Index(name = "idx_product_category", columnList = "category"),
        @Index(name = "idx_product_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mã SKU duy nhất của sản phẩm (Bắt buộc)
    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    // Tên sản phẩm (Bắt buộc)
    @Column(nullable = false, length = 200)
    private String name;

    // Nhóm hàng / Ngành hàng
    @Column(length = 100)
    private String category;

    // S2-06: Nhóm hàng trong cây nhóm hàng nhiều cấp (khi chuyển nhóm, ô category ở trên được cập nhật theo tên nhóm)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory productCategory;

    // Đơn vị tính cơ sở (Bắt buộc: vd: Lon, Chai, Hộp, Gói, Cái, Kg...)
    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    // Quy cách đóng gói (vd: Thùng 24 lon, Lốc 6 chai...)
    @Column(length = 100)
    private String packaging;

    // Giá vốn / Giá nhập (Chỉ Quản lý kinh doanh xem và sửa được theo S2-05)
    @Column(name = "cost_price", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal costPrice = BigDecimal.ZERO;

    // Trạng thái kinh doanh: ACTIVE (Đang kinh doanh), INACTIVE (Ngừng kinh doanh)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    // Mã vạch sản phẩm (Barcode / EAN-13...)
    @Column(length = 50)
    private String barcode;

    // Đường dẫn ảnh sản phẩm
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    // Mô tả chi tiết sản phẩm
    @Column(length = 1000)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Danh sách các đơn vị quy đổi (S2-07)
    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProductUnitConversion> unitConversions = new ArrayList<>();

    public void addUnitConversion(ProductUnitConversion conversion) {
        if (unitConversions == null) {
            unitConversions = new ArrayList<>();
        }
        unitConversions.add(conversion);
        conversion.setProduct(this);
    }

    public void removeUnitConversion(ProductUnitConversion conversion) {
        if (unitConversions != null) {
            unitConversions.remove(conversion);
            conversion.setProduct(null);
        }
    }
}
