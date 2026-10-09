package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * S3-09: Một dòng hàng của đơn.
 * - quantity + unitName: số lượng theo đơn vị người dùng chọn (thùng, lốc...), chỉ để hiển thị.
 * - baseQuantity: số lượng quy về đơn vị cơ sở (lon, chai...) — dùng để tính tiền, chiết khấu, tồn kho.
 * - unitPrice: giá bán trên 1 đơn vị cơ sở, lấy từ bảng giá đang hiệu lực của nhóm khách hàng.
 */
@Entity
@Table(name = "sales_order_lines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SalesOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private SalesOrder order;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "unit_name", nullable = false, length = 50)
    private String unitName;

    @Column(name = "conversion_factor", nullable = false, precision = 12, scale = 4)
    private BigDecimal conversionFactor;

    @Column(nullable = false, precision = 15, scale = 4)
    private BigDecimal quantity;

    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    @Column(name = "base_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal baseQuantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_list_id")
    private PriceList priceList;

    @Column(name = "unit_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal unitPrice;

    // S4-01: Đơn giá áp dụng theo đơn vị tính đã chọn (unitName). Bằng unitPrice * conversionFactor nếu dùng giá niêm yết
    @Column(name = "price_per_unit", precision = 15, scale = 2)
    private BigDecimal pricePerUnit;

    // S4-01: Đánh dấu dòng hàng đã sửa giá thủ công
    @Column(name = "is_custom_price")
    private Boolean isCustomPrice;

    @Column(name = "floor_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal floorPrice;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal grossAmount;

    @Column(name = "discount_policy_code", length = 40)
    private String discountPolicyCode;

    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal discountAmount;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal netAmount;
}
