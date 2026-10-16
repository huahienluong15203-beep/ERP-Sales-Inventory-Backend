package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * S8-03: Chi tiết từng dòng hàng điều chỉnh trong phiếu điều chỉnh tồn kho.
 * Lưu số lượng giảm, giá vốn và giá trị tổn thất của từng dòng.
 */
@Entity
@Table(name = "stock_adjustment_lines", indexes = {
        @Index(name = "idx_adjustment_line_order", columnList = "stock_adjustment_id"),
        @Index(name = "idx_adjustment_line_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockAdjustmentLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_adjustment_id", nullable = false)
    private StockAdjustment stockAdjustment;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    /** Tồn sổ hiện tại lúc lập phiếu (theo ĐVT cơ sở) */
    @Column(name = "current_stock", nullable = false, precision = 15, scale = 4)
    private BigDecimal currentStock;

    /** Số lượng điều chỉnh giảm do hỏng/mất/huỷ (theo ĐVT cơ sở, > 0) */
    @Column(name = "adjustment_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal adjustmentQuantity;

    /** Giá vốn tại thời điểm lập phiếu (costPrice) */
    @Column(name = "cost_price", nullable = false, precision = 15, scale = 2)
    private BigDecimal costPrice;

    /** Giá trị tổn thất = adjustmentQuantity * costPrice */
    @Column(name = "loss_value", nullable = false, precision = 18, scale = 2)
    private BigDecimal lossValue;

    @Column(name = "has_batch_management", nullable = false)
    @Builder.Default
    private Boolean hasBatchManagement = false;

    /** Số lô nếu sản phẩm quản lý theo lô */
    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "product_lot_id")
    private Long productLotId;

    @Column(name = "line_notes", length = 500)
    private String lineNotes;
}
