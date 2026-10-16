package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S8-01: Chi tiết từng dòng hàng trả trong phiếu trả hàng.
 * - Liên kết trực tiếp với dòng hoá đơn gốc (InvoiceLine).
 * - Lưu số lượng đã giao, số lượng đã trả trước đó và số lượng trả lần này.
 * - Lưu thông tin lô sản xuất nếu SKU có quản lý theo lô (hasBatchManagement = true).
 * - Cờ isDamaged: Hàng hỏng / lỗi nhập vào kho hàng lỗi hoặc khóa lô.
 */
@Entity
@Table(name = "sales_return_lines", indexes = {
        @Index(name = "idx_return_line_order", columnList = "sales_return_id"),
        @Index(name = "idx_return_line_inv_line", columnList = "invoice_line_id"),
        @Index(name = "idx_return_line_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SalesReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_return_id", nullable = false)
    private SalesReturn salesReturn;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_line_id", nullable = false)
    private InvoiceLine invoiceLine;

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

    /** Số lượng đã giao trên hoá đơn gốc theo đơn vị bán. */
    @Column(name = "delivered_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal deliveredQuantity;

    /** Số lượng đã trả trong các phiếu trả hàng trước đó. */
    @Column(name = "previously_returned_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal previouslyReturnedQuantity = BigDecimal.ZERO;

    /** Số lượng trả lần này (theo đơn vị bán). */
    @Column(name = "return_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal returnQuantity;

    /** Số lượng trả quy đổi ra ĐVT cơ sở để cộng tồn kho = returnQuantity * conversionFactor. */
    @Column(name = "base_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal baseQuantity;

    @Column(name = "price_per_unit", nullable = false, precision = 15, scale = 2)
    private BigDecimal pricePerUnit;

    @Column(name = "line_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal lineAmount;

    /** Sản phẩm có quản lý theo lô hay không. */
    @Column(name = "has_batch_management", nullable = false)
    @Builder.Default
    private Boolean hasBatchManagement = false;

    /** Số lô sản xuất (Bắt buộc nếu hasBatchManagement = true). */
    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "manufacture_date")
    private LocalDate manufactureDate;

    @Column(name = "expired_date")
    private LocalDate expiredDate;

    /** Hàng hỏng / lỗi nhập vào kho hàng lỗi hoặc bị khóa xuất bán. */
    @Column(name = "is_damaged", nullable = false)
    @Builder.Default
    private Boolean isDamaged = false;

    @Column(name = "line_notes", length = 500)
    private String lineNotes;
}
