package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S7-06: Biên bản đối chiếu công nợ định kỳ với đại lý.
 * Số liệu (đầu kỳ, phát sinh, cuối kỳ và các dòng chứng từ) được chụp lại lúc lập / chốt để 2 bên ký trên cùng 1 số liệu.
 * Biên bản đã chốt không sửa, không xoá; đại lý phản hồi lệch thì kế toán lập biên bản mới.
 */
@Entity
@Table(name = "debt_reconciliations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_debt_reconciliation_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_debt_reconciliation_customer", columnList = "customer_id"),
        @Index(name = "idx_debt_reconciliation_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DebtReconciliation {

    public static final String STATUS_DRAFT = "DRAFT";
    /** Kế toán đã chốt, chờ đại lý xác nhận. */
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_AGENCY_ACCEPTED = "AGENCY_ACCEPTED";
    public static final String STATUS_AGENCY_DISPUTED = "AGENCY_DISPUTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "customer_code", length = 30)
    private String customerCode;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Column(name = "customer_address", length = 500)
    private String customerAddress;

    @Column(name = "customer_tax_code", length = 30)
    private String customerTaxCode;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "opening_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal openingBalance;

    @Column(name = "total_debit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalDebit;

    @Column(name = "total_credit", nullable = false, precision = 18, scale = 2)
    private BigDecimal totalCredit;

    @Column(name = "closing_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal closingBalance;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_DRAFT;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_id", updatable = false)
    private Long createdById;

    @Column(name = "created_by_username", length = 50, updatable = false)
    private String createdByUsername;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "confirmed_by_id")
    private Long confirmedById;

    @Column(name = "confirmed_by", length = 150)
    private String confirmedBy;

    @Column(name = "agency_signed_at")
    private LocalDateTime agencySignedAt;

    @Column(name = "agency_signed_by_user_id")
    private Long agencySignedByUserId;

    @Column(name = "agency_signed_by", length = 150)
    private String agencySignedBy;

    @Column(name = "agency_feedback", length = 1000)
    private String agencyFeedback;

    @OneToMany(mappedBy = "reconciliation", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<DebtReconciliationLine> lines = new ArrayList<>();
}
