package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S8-02: Chứng từ điều chỉnh giảm trừ công nợ khi đại lý trả hàng (số GG2026-000001).
 * - Sinh từ phiếu trả hàng đã được thủ kho xác nhận nhận hàng; mỗi phiếu trả chỉ sinh 1 chứng từ.
 * - Giữ liên kết tới hoá đơn gốc; số tiền tính theo giá đã bán trên hoá đơn gốc (thành tiền sau chiết khấu của dòng).
 * - Hoá đơn gốc giữ nguyên (dòng hàng, tổng tiền); chỉ giảm số còn nợ qua các dòng đối trừ (allocations).
 * - Phần chưa đối trừ được (hoá đơn đã trả hết) là số dư có của đại lý, được trừ vào công nợ.
 * - Đã lập thì không sửa / xoá.
 */
@Entity
@Table(name = "credit_notes", uniqueConstraints = {
        @UniqueConstraint(name = "uk_credit_note_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_credit_note_sales_return", columnNames = "sales_return_id")
}, indexes = {
        @Index(name = "idx_credit_note_customer", columnList = "customer_id"),
        @Index(name = "idx_credit_note_invoice", columnList = "invoice_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class CreditNote {

    public static final String STATUS_CONFIRMED = "CONFIRMED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sales_return_id", nullable = false, updatable = false)
    private SalesReturn salesReturn;

    @Column(name = "sales_return_code", nullable = false, length = 30, updatable = false)
    private String salesReturnCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @Column(name = "invoice_code", nullable = false, length = 30, updatable = false)
    private String invoiceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "customer_code", nullable = false, length = 30, updatable = false)
    private String customerCode;

    @Column(name = "customer_name", nullable = false, length = 200, updatable = false)
    private String customerName;

    @Column(name = "note_date", nullable = false, updatable = false)
    private LocalDate noteDate;

    /** Tổng tiền giảm trừ (theo giá trên hoá đơn gốc). */
    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal amount;

    /** Phần đã đối trừ vào các hoá đơn còn nợ. */
    @Column(name = "applied_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal appliedAmount;

    /** Phần chưa đối trừ được (số dư có của đại lý), được trừ vào công nợ hiện tại. */
    @Column(name = "unapplied_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal unappliedAmount;

    @Column(nullable = false, length = 30, updatable = false)
    @Builder.Default
    private String status = STATUS_CONFIRMED;

    @Column(name = "return_reason", length = 50, updatable = false)
    private String returnReason;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by_id", updatable = false)
    private Long createdById;

    @Column(name = "created_by_username", length = 50, updatable = false)
    private String createdByUsername;

    @OneToMany(mappedBy = "creditNote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<CreditNoteLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "creditNote", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    @Builder.Default
    private List<CreditNoteAllocation> allocations = new ArrayList<>();

    public void addLine(CreditNoteLine line) {
        line.setCreditNote(this);
        lines.add(line);
    }

    public void addAllocation(CreditNoteAllocation allocation) {
        allocation.setCreditNote(this);
        allocations.add(allocation);
    }

    /** Quy tắc 7: chứng từ đã lập không được xoá, chỉ lập chứng từ điều chỉnh khác. */
    @PreRemove
    void preventDelete() {
        throw new IllegalStateException("Chứng từ giảm trừ công nợ đã lập không được xoá");
    }
}
