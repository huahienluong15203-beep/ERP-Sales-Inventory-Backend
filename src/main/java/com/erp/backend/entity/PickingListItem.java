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
 * S6-03: Dòng hàng đã được gộp từ nhiều đơn, gắn vị trí lưu kho và sắp xếp theo đường đi.
 */
@Entity
@Table(name = "picking_list_items", indexes = {
        @Index(name = "idx_pli_picking_list", columnList = "picking_list_id"),
        @Index(name = "idx_pli_product", columnList = "product_id"),
        @Index(name = "idx_pli_location", columnList = "location_id"),
        @Index(name = "idx_pli_route", columnList = "route_sequence")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PickingListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "picking_list_id", nullable = false)
    private PickingList pickingList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "product_sku", nullable = false, length = 50)
    private String productSku;

    @Column(name = "product_name", nullable = false, length = 200)
    private String productName;

    @Column(name = "base_unit", nullable = false, length = 30)
    private String baseUnit;

    // Vị trí lưu kho (Khu vực / Kệ / Ô / Ngăn)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id")
    private WarehouseLocation location;

    @Column(name = "location_code", length = 50)
    private String locationCode;

    @Column(name = "location_name", length = 150)
    private String locationName;

    @Column(name = "zone_name", length = 100)
    private String zone;

    @Column(name = "shelf_name", length = 100)
    private String shelf;

    // Thứ tự đi lấy hàng trong kho (1, 2, 3...)
    @Column(name = "route_sequence")
    private Integer routeSequence;

    // Lô hàng gợi ý theo nguyên tắc FEFO (nếu SKU quản lý theo lô)
    @Column(name = "suggested_batch_number", length = 100)
    private String suggestedBatchNumber;

    @Column(name = "suggested_expired_date")
    private LocalDate suggestedExpiredDate;

    // Lô hàng thực tế thủ kho lấy (nếu có đổi lô)
    @Column(name = "actual_batch_number", length = 100)
    private String actualBatchNumber;

    // S6-04: Lý do đổi lô khác với lô gợi ý FEFO (bắt buộc khi actualBatchNumber khác suggestedBatchNumber)
    @Column(name = "lot_change_reason", length = 500)
    private String lotChangeReason;

    // Tổng số lượng cần soạn (tính theo ĐVT cơ sở) gộp từ tất cả các đơn
    @Column(name = "requested_quantity", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal requestedQuantity = BigDecimal.ZERO;

    // Số lượng thực tế thủ kho đã soạn được
    @Column(name = "picked_quantity", nullable = false, precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal pickedQuantity = BigDecimal.ZERO;

    // Đánh dấu đã soạn xong dòng này (checkbox / tick trên mobile)
    @Column(name = "is_picked", nullable = false)
    @Builder.Default
    private Boolean isPicked = false;

    // S6-03: Lý do soạn thiếu (bắt buộc khi pickedQuantity < requestedQuantity)
    @Column(name = "shortage_reason", length = 500)
    private String shortageReason;

    @Column(length = 500)
    private String notes;

    @OneToMany(mappedBy = "pickingListItem", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PickingListItemAllocation> allocations = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void addAllocation(PickingListItemAllocation allocation) {
        allocation.setPickingListItem(this);
        allocations.add(allocation);
    }
}
