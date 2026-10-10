package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S5-07: Chi tiết dòng hàng điều chuyển kho nội bộ.
 * - transferQuantity: Số lượng xuất đi từ kho nguồn
 * - receivedQuantity: Số lượng thực tế kho đến nhận
 * - differenceQuantity: transferQuantity - receivedQuantity
 * - discrepancyReason: Bắt buộc nhập khi differenceQuantity != 0 (AC4)
 */
@Entity
@Table(name = "stock_transfer_lines", indexes = {
        @Index(name = "idx_stl_transfer", columnList = "stock_transfer_id"),
        @Index(name = "idx_stl_product", columnList = "product_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockTransferLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_transfer_id", nullable = false)
    private StockTransfer stockTransfer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(length = 100)
    private String category;

    @Column(nullable = false, length = 30)
    private String unit;

    // Tồn khả dụng tại kho đi thời điểm tạo
    @Column(name = "source_available_stock", precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal sourceAvailableStock = BigDecimal.ZERO;

    // Số lượng điều chuyển xuất đi
    @Column(name = "transfer_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal transferQuantity = BigDecimal.ZERO;

    // Số lượng thực tế kho đích nhận (null khi đang đi đường)
    @Column(name = "received_quantity", precision = 15, scale = 4)
    private BigDecimal receivedQuantity;

    // Chênh lệch (transfer - received)
    @Column(name = "difference_quantity", precision = 15, scale = 4)
    private BigDecimal differenceQuantity;

    // Lý do chênh lệch riêng cho dòng này (AC4)
    @Column(name = "discrepancy_reason", length = 500)
    private String discrepancyReason;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "expired_date")
    private LocalDate expiredDate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;
}
