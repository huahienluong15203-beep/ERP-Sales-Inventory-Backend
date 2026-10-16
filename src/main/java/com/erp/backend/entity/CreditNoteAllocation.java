package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** S8-02: Phần giảm trừ đối trừ vào 1 hoá đơn còn nợ (ghi số còn nợ trước / sau để đối chiếu). */
@Entity
@Table(name = "credit_note_allocations", indexes = {
        @Index(name = "idx_credit_note_alloc_invoice", columnList = "invoice_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class CreditNoteAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "credit_note_id", nullable = false, updatable = false)
    private CreditNote creditNote;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(name = "invoice_code", nullable = false, length = 30, updatable = false)
    private String invoiceCode;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    @Column(name = "remaining_before", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal remainingBefore;

    @Column(name = "remaining_after", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal remainingAfter;
}
