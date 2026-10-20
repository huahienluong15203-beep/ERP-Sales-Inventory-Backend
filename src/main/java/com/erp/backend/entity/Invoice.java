package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S7-01: Hoá đơn bán hàng lập từ số lượng THỰC GIAO của một hoặc nhiều đơn đã giao của cùng 1 đại lý.
 * - Số hoá đơn liên tục theo năm, không trùng, không nhảy số (cấp trong cùng transaction từ bảng invoice_sequences).
 * - Hoá đơn đã phát hành KHÔNG sửa / xoá: các cột nghiệp vụ updatable = false, chặn xoá ở @PreRemove;
 *   sai sót thì lập chứng từ điều chỉnh (làm ở story sau).
 * - Lưu ảnh chụp thông tin đại lý lúc phát hành (đại lý đổi tên / địa chỉ sau đó không làm đổi hoá đơn cũ).
 */
@Entity
@Table(name = "invoices", uniqueConstraints = {
        @UniqueConstraint(name = "uk_invoice_code", columnNames = "code"),
        @UniqueConstraint(name = "uk_invoice_year_number", columnNames = {"seq_year", "seq_number"})
}, indexes = {
        @Index(name = "idx_invoice_customer", columnList = "customer_id"),
        @Index(name = "idx_invoice_date", columnList = "invoice_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class Invoice {

    public static final String STATUS_ISSUED = "ISSUED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(name = "seq_year", nullable = false, updatable = false)
    private Integer seqYear;

    @Column(name = "seq_number", nullable = false, updatable = false)
    private Long seqNumber;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false, updatable = false)
    private Customer customer;

    @Column(name = "customer_code", nullable = false, length = 30, updatable = false)
    private String customerCode;

    @Column(name = "customer_name", nullable = false, length = 200, updatable = false)
    private String customerName;

    @Column(name = "customer_tax_code", length = 20, updatable = false)
    private String customerTaxCode;

    @Column(name = "customer_address", length = 500, updatable = false)
    private String customerAddress;

    /** Khoảng ngày giao hàng mà hoá đơn gộp (để đối chiếu). */
    @Column(name = "period_from", updatable = false)
    private LocalDate periodFrom;

    @Column(name = "period_to", updatable = false)
    private LocalDate periodTo;

    @Column(nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal subtotal;

    @Column(name = "discount_total", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal discountTotal;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    public static final String PAYMENT_STATUS_UNPAID = "UNPAID";
    public static final String PAYMENT_STATUS_PARTIALLY_PAID = "PARTIALLY_PAID";
    public static final String PAYMENT_STATUS_PAID = "PAID";

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_ISSUED;

    /** S7-02: Số tiền đã thanh toán cho hoá đơn này */
    @Column(name = "paid_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /** S7-02: Số tiền còn nợ của hoá đơn này */
    @Column(name = "remaining_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal remainingAmount = BigDecimal.ZERO;

    /** S7-02: Trạng thái thanh toán công nợ: UNPAID, PARTIALLY_PAID, PAID */
    @Column(name = "payment_status", nullable = false, length = 30)
    @Builder.Default
    private String paymentStatus = PAYMENT_STATUS_UNPAID;

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    @Column(name = "issued_by_id", updatable = false)
    private Long issuedById;

    @Column(name = "issued_by_username", length = 50, updatable = false)
    private String issuedByUsername;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<InvoiceLine> lines = new ArrayList<>();

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        lines.add(line);
    }

    @PrePersist
    void prePersist() {
        if (paidAmount == null) {
            paidAmount = BigDecimal.ZERO;
        }
        if (remainingAmount == null) {
            remainingAmount = totalAmount != null ? totalAmount : BigDecimal.ZERO;
        }
        if (paymentStatus == null) {
            paymentStatus = PAYMENT_STATUS_UNPAID;
        }
    }

    /**
     * S7-02: Cập nhật cấn trừ thanh toán vào hoá đơn.
     */
    public void applyPayment(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        if (this.paidAmount == null) {
            this.paidAmount = BigDecimal.ZERO;
        }
        if (this.remainingAmount == null) {
            this.remainingAmount = this.totalAmount != null ? this.totalAmount : BigDecimal.ZERO;
        }
        this.paidAmount = this.paidAmount.add(amount);
        this.remainingAmount = this.remainingAmount.subtract(amount);
        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.remainingAmount = BigDecimal.ZERO;
            this.paymentStatus = PAYMENT_STATUS_PAID;
        } else {
            this.paymentStatus = PAYMENT_STATUS_PARTIALLY_PAID;
        }
    }

    /**
     * S7-02: Hoàn tác cấn trừ thanh toán khi lập bút toán điều chỉnh.
     */
    public void revertPayment(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return;
        }
        if (this.paidAmount == null) {
            this.paidAmount = BigDecimal.ZERO;
        }
        if (this.remainingAmount == null) {
            this.remainingAmount = BigDecimal.ZERO;
        }
        this.paidAmount = this.paidAmount.subtract(amount).max(BigDecimal.ZERO);
        BigDecimal maxRemaining = this.totalAmount != null ? this.totalAmount : this.remainingAmount.add(amount);
        this.remainingAmount = this.remainingAmount.add(amount).min(maxRemaining);
        if (this.remainingAmount.compareTo(BigDecimal.ZERO) <= 0) {
            this.paymentStatus = PAYMENT_STATUS_PAID;
        } else if (this.paidAmount.compareTo(BigDecimal.ZERO) > 0) {
            this.paymentStatus = PAYMENT_STATUS_PARTIALLY_PAID;
        } else {
            this.paymentStatus = PAYMENT_STATUS_UNPAID;
        }
    }

    /**
     * S7-08: Hạn thanh toán hoá đơn = Ngày phát hành + Số ngày nợ tối đa của đại lý (mặc định 30 ngày).
     */
    public LocalDate calculateDueDate() {
        Integer maxDebt = customer != null ? customer.getMaxDebtDays() : null;
        int days = (maxDebt != null && maxDebt > 0) ? maxDebt : 30;
        return invoiceDate != null ? invoiceDate.plusDays(days) : LocalDate.now();
    }

    /** Quy tắc 7: chứng từ đã phát hành không được xoá. */
    @PreRemove
    void preventDelete() {
        throw new IllegalStateException("Hoá đơn đã phát hành không được xoá, chỉ lập chứng từ điều chỉnh");
    }
}
