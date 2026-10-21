package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S5-07 / SCRUM-163: Phiếu chuyển kho nội bộ (Stock Transfer).
 * Acceptance Criteria:
 * - AC1: Chọn kho đi, kho đến, danh sách hàng và số lượng
 * - AC2: Hàng đang chuyển ghi nhận là Đang trên đường (IN_TRANSIT), đã trừ kho đi, chưa cộng kho đến
 * - AC3: Kho đến xác nhận nhận đủ (COMPLETED) thì tồn mới được cộng
 * - AC4: Chênh lệch khi nhận (DISCREPANCY_RESOLVED) phải nhập lý do
 */
@Entity
@Table(name = "stock_transfers", indexes = {
        @Index(name = "idx_stock_transfer_code", columnList = "code", unique = true),
        @Index(name = "idx_stock_transfer_source", columnList = "source_warehouse_id"),
        @Index(name = "idx_stock_transfer_dest", columnList = "dest_warehouse_id"),
        @Index(name = "idx_stock_transfer_status", columnList = "status"),
        @Index(name = "idx_stock_transfer_date", columnList = "transfer_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class StockTransfer {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_IN_TRANSIT = "IN_TRANSIT";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_DISCREPANCY_RESOLVED = "DISCREPANCY_RESOLVED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mã phiếu điều chuyển (vd: TRF-202610-001)
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    // Kho đi (Kho xuất)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_warehouse_id", nullable = false)
    private Warehouse sourceWarehouse;

    @Column(name = "source_warehouse_code", length = 50)
    private String sourceWarehouseCode;

    @Column(name = "source_warehouse_name", length = 150)
    private String sourceWarehouseName;

    // Kho đến (Kho tiếp nhận)
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dest_warehouse_id", nullable = false)
    private Warehouse destWarehouse;

    @Column(name = "dest_warehouse_code", length = 50)
    private String destWarehouseCode;

    @Column(name = "dest_warehouse_name", length = 150)
    private String destWarehouseName;

    // Ngày điều chuyển
    @Column(name = "transfer_date", nullable = false)
    private LocalDate transferDate;

    // Ngày dự kiến đến
    @Column(name = "expected_receive_date")
    private LocalDate expectedReceiveDate;

    // DRAFT | IN_TRANSIT | COMPLETED | DISCREPANCY_RESOLVED | CANCELLED
    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_DRAFT;

    // Biển số xe vận chuyển
    @Column(name = "vehicle_plate", length = 50)
    private String vehiclePlate;

    // Tài xế / Đơn vị vận chuyển
    @Column(name = "transporter_name", length = 150)
    private String transporterName;

    @Column(length = 500)
    private String note;

    // Lý do giải trình chung khi nhận chênh lệch (AC4)
    @Column(name = "discrepancy_general_reason", length = 500)
    private String discrepancyGeneralReason;

    // Người lập phiếu
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_by_username", length = 100)
    private String createdByUsername;

    // Thời điểm xuất kho bắt đầu đi đường (AC2)
    @Column(name = "dispatched_at")
    private LocalDateTime dispatchedAt;

    // Thời điểm kho đến nhận hàng hoàn tất (AC3)
    @Column(name = "received_at")
    private LocalDateTime receivedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamp default now()")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "stockTransfer", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<StockTransferLine> lines = new ArrayList<>();

    public void addLine(StockTransferLine line) {
        if (lines == null) {
            lines = new ArrayList<>();
        }
        lines.add(line);
        line.setStockTransfer(this);
    }
}
