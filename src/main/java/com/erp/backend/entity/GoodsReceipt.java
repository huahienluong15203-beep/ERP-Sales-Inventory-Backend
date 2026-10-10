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
 * S5-04 / SCRUM-162: Phiếu nhập kho từ nhà cung cấp.
 * - Khai báo nhà cung cấp, số chứng từ, ngày nhập, kho nhập
 * - Lưu nháp (DRAFT) hoặc Xác nhận (CONFIRMED)
 * - Xác nhận phiếu mới cộng tồn kho; phiếu nháp không ảnh hưởng tồn
 * - Phiếu đã xác nhận không sửa được, chỉ lập phiếu điều chỉnh
 */
@Entity
@Table(name = "goods_receipts", indexes = {
        @Index(name = "idx_goods_receipt_code", columnList = "code", unique = true),
        @Index(name = "idx_goods_receipt_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_goods_receipt_supplier", columnList = "supplier_id"),
        @Index(name = "idx_goods_receipt_status", columnList = "status"),
        @Index(name = "idx_goods_receipt_date", columnList = "receipt_date")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class GoodsReceipt {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mã phiếu nhập kho tự sinh (vd: PNK-202610-001)
    @Column(nullable = false, unique = true, length = 40)
    private String code;

    // Nhà cung cấp giao hàng
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "supplier_code", length = 50)
    private String supplierCode;

    @Column(name = "supplier_name", length = 200)
    private String supplierName;

    // Số chứng từ / hóa đơn giao hàng từ nhà cung cấp
    @Column(name = "document_number", nullable = false, length = 100)
    private String documentNumber;

    // Ngày nhập kho thực tế khi dỡ hàng
    @Column(name = "receipt_date", nullable = false)
    private LocalDate receiptDate;

    // Kho hàng tiếp nhận
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(name = "warehouse_code", length = 50)
    private String warehouseCode;

    @Column(name = "warehouse_name", length = 150)
    private String warehouseName;

    // DRAFT (Nháp - chưa cộng tồn) | CONFIRMED (Đã xác nhận - đã cộng tồn) | CANCELLED
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_DRAFT;

    // Biển số xe giao hàng
    @Column(name = "vehicle_plate", length = 50)
    private String vehiclePlate;

    // Tên lái xe giao hàng
    @Column(name = "driver_name", length = 100)
    private String driverName;

    @Column(length = 500)
    private String note;

    @Column(name = "total_lines", nullable = false)
    @Builder.Default
    private Integer totalLines = 0;

    // Tổng số lượng quy về đơn vị cơ sở
    @Column(name = "total_base_quantity", nullable = false, precision = 15, scale = 4)
    @Builder.Default
    private BigDecimal totalBaseQuantity = BigDecimal.ZERO;

    // Tổng tiền giá trị phiếu nhập
    @Column(name = "total_amount", nullable = false, precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    // Người tạo phiếu
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private User createdBy;

    @Column(name = "created_by_username", length = 100)
    private String createdByUsername;

    // Thời điểm và người xác nhận nhập kho
    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_by_id")
    private User confirmedBy;

    @Column(name = "confirmed_by_username", length = 100)
    private String confirmedByUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamp default now()")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamp default now()")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<GoodsReceiptLine> lines = new ArrayList<>();

    public void addLine(GoodsReceiptLine line) {
        if (lines == null) {
            lines = new ArrayList<>();
        }
        lines.add(line);
        line.setGoodsReceipt(this);
    }
}
