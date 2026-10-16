package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/**
 * S7-02: Chi tiết phân bổ cấn trừ thanh toán vào hoá đơn nợ.
 */
@Entity
@Table(name = "payment_allocations", indexes = {
        @Index(name = "idx_allocation_receipt", columnList = "payment_receipt_id"),
        @Index(name = "idx_allocation_invoice", columnList = "invoice_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_receipt_id", nullable = false, updatable = false)
    private PaymentReceipt paymentReceipt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(name = "invoice_code", nullable = false, length = 30, updatable = false)
    private String invoiceCode;

    @Column(name = "order_code", length = 30, updatable = false)
    private String orderCode;

    @Column(name = "allocated_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal allocatedAmount;

    @Column(name = "invoice_total", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal invoiceTotal;

    @Column(name = "remaining_before", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal remainingBefore;

    @Column(name = "remaining_after", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal remainingAfter;

    @PreRemove
    public void preventDelete() {
        throw new IllegalStateException("Bút toán cấn trừ đã ghi nhận không được xoá, chỉ lập bút toán điều chỉnh");
    }
}
