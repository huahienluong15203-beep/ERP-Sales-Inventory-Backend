package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** S8-02: Dòng giảm trừ theo từng dòng hàng trả (giá lấy từ dòng hoá đơn gốc). */
@Entity
@Table(name = "credit_note_lines", indexes = {
        @Index(name = "idx_credit_note_line_invoice_line", columnList = "invoice_line_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class CreditNoteLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    private CreditNote creditNote;

    @Column(name = "line_no", nullable = false, updatable = false)
    private Integer lineNo;

    @Column(name = "sales_return_line_id", nullable = false, updatable = false)
    private Long salesReturnLineId;

    @Column(name = "invoice_line_id", nullable = false, updatable = false)
    private Long invoiceLineId;

    @Column(name = "product_sku", nullable = false, length = 50, updatable = false)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200, updatable = false)
    private String productName;

    @Column(name = "unit_name", length = 50, updatable = false)
    private String unitName;

    /** Số lượng trả (theo ĐVT trên hoá đơn). */
    @Column(name = "return_quantity", nullable = false, precision = 15, scale = 4, updatable = false)
    private BigDecimal returnQuantity;

    /** Số lượng và thành tiền (sau chiết khấu) của dòng hoá đơn gốc, để đối chiếu giá đã bán. */
    @Column(name = "invoice_quantity", nullable = false, precision = 15, scale = 4, updatable = false)
    private BigDecimal invoiceQuantity;

    @Column(name = "invoice_net_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal invoiceNetAmount;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;
}
