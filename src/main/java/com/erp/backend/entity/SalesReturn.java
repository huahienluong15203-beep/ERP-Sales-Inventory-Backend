package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S8-01: Phiếu trả hàng từ đại lý và nhập lại kho (Sales Return Order).
 * - NVKD lập phiếu chọn hoá đơn gốc, chọn dòng hàng và số lượng trả, bắt buộc chọn lý do trả.
 * - Số lượng trả không vượt quá số lượng đã giao trừ phần đã trả trước đó.
 * - Hàng trả về nhập lại đúng lô nếu SKU có quản lý lô; hàng hỏng nhập vào kho hàng lỗi / khóa lô.
 * - Phiếu trả phải được Quản lý kho xác nhận nhận hàng mới cộng tồn.
 */
@Entity
@Table(name = "sales_returns", uniqueConstraints = {
        @UniqueConstraint(name = "uk_sales_return_code", columnNames = "code")
}, indexes = {
        @Index(name = "idx_sales_return_invoice", columnList = "invoice_id"),
        @Index(name = "idx_sales_return_customer", columnList = "customer_id"),
        @Index(name = "idx_sales_return_warehouse", columnList = "receiving_warehouse_id"),
        @Index(name = "idx_sales_return_date", columnList = "return_date"),
        @Index(name = "idx_sales_return_status", columnList = "status")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SalesReturn {

    public static final String STATUS_PENDING_RECEIPT = "PENDING_RECEIPT"; // Chờ thủ kho nhận hàng
    public static final String STATUS_RECEIVED = "RECEIVED";              // Thủ kho đã xác nhận nhận hàng và cộng tồn
    public static final String STATUS_CANCELLED = "CANCELLED";            // Đã huỷ

    // Lý do trả hàng bắt buộc theo AC
    public static final String REASON_DEFECTIVE_TRANSPORT = "DEFECTIVE_TRANSPORT";   // Hỏng/vỡ do vận chuyển
    public static final String REASON_EXPIRED_OR_NEAR = "EXPIRED_OR_NEAR";           // Hết hạn / cận hạn sử dụng
    public static final String REASON_WRONG_SPECIFICATION = "WRONG_SPECIFICATION";   // Sai quy cách / không đúng mẫu
    public static final String REASON_AGENCY_OVERSTOCK = "AGENCY_OVERSTOCK";         // Đại lý thừa hàng / giảm quy mô
    public static final String REASON_CUSTOMER_COMPLAINT = "CUSTOMER_COMPLAINT";     // Khiếu nại chất lượng
    public static final String REASON_OTHER = "OTHER";                               // Lý do khác

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "return_date", nullable = false)
    private LocalDate returnDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "invoice_code", nullable = false, length = 30)
    private String invoiceCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(name = "customer_code", nullable = false, length = 30)
    private String customerCode;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiving_warehouse_id", nullable = false)
    private Warehouse receivingWarehouse;

    @Column(name = "warehouse_code", nullable = false, length = 30)
    private String warehouseCode;

    @Column(name = "warehouse_name", nullable = false, length = 150)
    private String warehouseName;

    /** Bắt buộc chọn lý do trả hàng (AC1). */
    @Column(name = "return_reason", nullable = false, length = 50)
    private String returnReason;

    /** Diễn giải chi tiết lý do trả hàng. */
    @Column(name = "reason_description", length = 500)
    private String reasonDescription;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_PENDING_RECEIPT;

    @Column(name = "total_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(length = 500)
    private String notes;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_name", length = 100)
    private String createdByName;

    @Column(name = "received_by_id")
    private Long receivedById;

    @Column(name = "received_by_name", length = 100)
    private String receivedByName;

    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "salesReturn", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<SalesReturnLine> lines = new ArrayList<>();

    public void addLine(SalesReturnLine line) {
        lines.add(line);
        line.setSalesReturn(this);
    }
}
