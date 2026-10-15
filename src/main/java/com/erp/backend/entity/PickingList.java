package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * S6-03 / SCRUM-88: Phiếu soạn hàng (Picking List / Batch Picking).
 * Gom nhiều đơn đã duyệt cùng kho, gộp mặt hàng và sắp xếp theo đường đi trong kho.
 */
@Entity
@Table(name = "picking_lists", indexes = {
        @Index(name = "idx_picking_list_warehouse", columnList = "warehouse_id"),
        @Index(name = "idx_picking_list_status", columnList = "status"),
        @Index(name = "idx_picking_list_code", columnList = "code")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class PickingList {

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_ASSIGNED = "ASSIGNED";
    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Mã phiếu soạn hàng (vd: PKL-202610-0001)
    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String status = STATUS_ASSIGNED;

    // Nhân viên được phân công soạn hàng (picker)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "picker_id")
    private User picker;

    @Column(name = "picker_name", length = 100)
    private String pickerName;

    @Column(length = 500)
    private String note;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    @Column(name = "total_orders")
    @Builder.Default
    private Integer totalOrders = 0;

    @Column(name = "total_items")
    @Builder.Default
    private Integer totalItems = 0;

    @Column(name = "total_requested_quantity", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalRequestedQuantity = BigDecimal.ZERO;

    @Column(name = "total_picked_quantity", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalPickedQuantity = BigDecimal.ZERO;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_by_id")
    private Long createdById;

    @Column(name = "created_by_username", length = 50)
    private String createdByUsername;

    @OneToMany(mappedBy = "pickingList", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<PickingListOrder> orders = new ArrayList<>();

    @OneToMany(mappedBy = "pickingList", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("routeSequence ASC, id ASC")
    @Builder.Default
    private List<PickingListItem> items = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void addOrder(PickingListOrder order) {
        order.setPickingList(this);
        orders.add(order);
    }

    public void addItem(PickingListItem item) {
        item.setPickingList(this);
        items.add(item);
    }
}
