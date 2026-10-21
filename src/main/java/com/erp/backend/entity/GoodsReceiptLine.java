package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S5-04: Chi tiết dòng hàng trong phiếu nhập kho.
 * - Cho phép nhập theo đơn vị tính bất kỳ (thùng, lốc, lon, v.v.)
 * - Quy về đơn vị cơ sở: baseQuantity = quantity * conversionFactor
 * - Lưu số lô (batchNumber) và hạn dùng (expiredDate) cho mặt hàng quản lý lô.
 */
@Entity
@Table(name = "goods_receipt_lines", indexes = {
        @Index(name = "idx_grl_receipt", columnList = "goods_receipt_id"),
        @Index(name = "idx_grl_product", columnList = "product_id"),
        @Index(name = "idx_grl_batch", columnList = "batch_number")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class GoodsReceiptLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private GoodsReceipt goodsReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(length = 100)
    private String category;

    // Đơn vị cơ sở chuẩn (vd: Lon, Chai, Hộp)
    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    // Đơn vị tính được chọn khi nhập (vd: Thùng, Lốc, Lon)
    @Column(name = "selected_unit", nullable = false, length = 50)
    private String selectedUnit;

    // Hệ số quy đổi về đơn vị cơ sở (vd: 1 Thùng = 24 Lon -> 24.0000)
    @Column(name = "conversion_factor", nullable = false, precision = 12, scale = 4)
    @Builder.Default
    private BigDecimal conversionFactor = BigDecimal.ONE;

    // Số lượng theo đơn vị nhập
    @Column(nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal quantity = BigDecimal.ZERO;

    // Số lượng quy về đơn vị cơ sở (ghi sổ tồn kho)
    @Column(name = "base_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal baseQuantity = BigDecimal.ZERO;

    // Đơn giá nhập
    @Column(name = "unit_price", precision = 15, scale = 2)
    @Builder.Default
    private BigDecimal unitPrice = BigDecimal.ZERO;

    // Thành tiền dòng
    @Column(name = "total_amount", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // Số lô sản xuất (AC3)
    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    // Hạn sử dụng (AC3)
    @Column(name = "expired_date")
    private LocalDate expiredDate;

    // Cờ đánh dấu mặt hàng có quản lý lô
    @Column(name = "has_batch_management")
    @Builder.Default
    private Boolean hasBatchManagement = false;

    @Column(length = 500)
    private String note;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;
}
