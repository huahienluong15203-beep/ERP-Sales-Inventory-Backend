package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * S7-01: Dòng hoá đơn = phần THỰC GIAO của 1 dòng đơn hàng. Không sửa / xoá.
 * order_line_id là duy nhất: 1 dòng đơn chỉ được lập hoá đơn 1 lần (chặn ở cả DB).
 */
@Entity
@Table(name = "invoice_lines", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_line_order_line", columnNames = "order_line_id")
}, indexes = {
        @Index(name = "idx_invoice_line_invoice", columnList = "invoice_id"),
        @Index(name = "idx_invoice_line_order", columnList = "order_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class InvoiceLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(name = "line_no", nullable = false, updatable = false)
    private Integer lineNo;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "order_code", nullable = false, length = 30, updatable = false)
    private String orderCode;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private Long orderLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50, updatable = false)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "unit_name", nullable = false, length = 50, updatable = false)
    private String unitName;

    @Column(name = "conversion_factor", nullable = false, precision = 12, scale = 4, updatable = false)
    private BigDecimal conversionFactor;

    /** Số lượng thực giao theo unitName. */
    @Column(nullable = false, precision = 15, scale = 4, updatable = false)
    private BigDecimal quantity;

    @Column(name = "base_unit", nullable = false, length = 30, updatable = false)
    private String baseUnit;

    @Column(name = "base_quantity", nullable = false, precision = 18, scale = 4, updatable = false)
    private BigDecimal baseQuantity;

    @Column(name = "price_per_unit", nullable = false, precision = 15, scale = 2, updatable = false)
    private BigDecimal pricePerUnit;

    @Column(name = "gross_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal grossAmount;

    @Column(name = "discount_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal discountAmount;

    @Column(name = "net_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal netAmount;

    @PreRemove
    void preventDelete() {
        throw new IllegalStateException("Dòng hoá đơn đã phát hành không được xoá");
    }
}
