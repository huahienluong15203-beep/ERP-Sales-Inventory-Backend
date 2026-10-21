package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/** S7-06: Dòng chứng từ phát sinh trong kỳ trên biên bản đối chiếu (bản chụp số liệu). */
@Entity
@Table(name = "debt_reconciliation_lines", indexes = {
        @Index(name = "idx_debt_reconciliation_line_parent", columnList = "reconciliation_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DebtReconciliationLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reconciliation_id", nullable = false)
    private DebtReconciliation reconciliation;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "tx_date")
    private LocalDate txDate;

    /** INVOICE · PAYMENT · ADJUSTMENT */
    @Column(name = "doc_type", nullable = false, length = 30)
    private String docType;

    @Column(name = "type_label", length = 100)
    private String typeLabel;

    @Column(name = "doc_id")
    private Long docId;

    @Column(name = "doc_code", length = 50)
    private String docCode;

    @Column(length = 500)
    private String description;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal debit;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal credit;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal balance;
}
