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
 * S3-09: Đơn hàng bán cho đại lý. Sprint này mới có trạng thái DRAFT (đơn nháp, lưu và mở lại gõ tiếp).
 * Chốt đơn, giữ tồn, kiểm tra công nợ làm ở Sprint 4.
 * S4-02: đơn đã duyệt (APPROVED) được tính vào công nợ hiện tại của đại lý, tính hạn nợ từ approvedAt.
 * S4-05: chốt đơn DRAFT -> APPROVED (không vi phạm) hoặc PENDING_APPROVAL (vượt hạn mức / dưới giá sàn);
 *        QL kinh doanh Duyệt -> APPROVED, Từ chối -> REJECTED, Trả lại sửa -> DRAFT.
 */
@Entity
@Table(name = "sales_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class SalesOrder {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    /**
     * S4-02: Trạng thái đơn đang tính vào công nợ của đại lý.
     * Chưa có hoá đơn / phiếu thu nên tạm coi đơn đã duyệt là đang nợ; có module công nợ thì sửa ở đây.
     */
    public static final List<String> OUTSTANDING_DEBT_STATUSES = List.of(STATUS_APPROVED);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    // Chống 2 người cùng lưu một đơn nháp đè lên nhau
    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "delivery_address_id")
    private CustomerDeliveryAddress deliveryAddress;

    @Column(name = "desired_delivery_date")
    private LocalDate desiredDeliveryDate;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    @Column(length = 500)
    private String note;

    // Tổng tiền hàng (trước chiết khấu), tổng chiết khấu, tổng phải thu (VND)
    @Column(nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // S4-02: thời điểm đơn được duyệt, dùng làm ngày bắt đầu tính hạn nợ (maxDebtDays của đại lý)
    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    // S4-05: người duyệt (null nếu đơn tự duyệt vì không vi phạm)
    @Column(name = "approved_by_id")
    private Long approvedById;

    @Column(name = "approved_by_username", length = 50)
    private String approvedByUsername;

    // S4-05: lần chốt đơn gần nhất
    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by_username", length = 50)
    private String submittedByUsername;

    // S4-05: ý kiến gần nhất của người duyệt (lý do Từ chối / Trả lại sửa) để NV kinh doanh xem và sửa
    @Column(name = "last_approval_comment", length = 500)
    private String lastApprovalComment;

    // S4-05: mức vi phạm tính lúc chốt đơn (null = không vi phạm)
    @Column(name = "credit_exceeded_amount", precision = 18, scale = 2)
    private BigDecimal creditExceededAmount;

    @Column(name = "credit_exceeded_percent", precision = 9, scale = 2)
    private BigDecimal creditExceededPercent;

    @Column(name = "below_floor_line_count")
    private Integer belowFloorLineCount;

    // Tổng tiền thiếu so với giá sàn của các dòng bán dưới giá sàn
    @Column(name = "below_floor_amount", precision = 18, scale = 2)
    private BigDecimal belowFloorAmount;

    // % thấp hơn giá sàn của dòng vi phạm nặng nhất
    @Column(name = "below_floor_max_percent", precision = 9, scale = 2)
    private BigDecimal belowFloorMaxPercent;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_username", length = 50)
    private String createdByUsername;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<SalesOrderLine> lines = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void addLine(SalesOrderLine line) {
        line.setOrder(this);
        lines.add(line);
    }
}
