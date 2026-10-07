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
