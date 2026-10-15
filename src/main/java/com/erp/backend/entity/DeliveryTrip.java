package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S6-07: Chuyến giao hàng = một xe chở nhiều đơn đã xuất kho (mỗi đơn là 1 điểm giao).
 * - Đang chạy (IN_PROGRESS) từ lúc xe rời kho đến khi xe về và kho xác nhận kết thúc chuyến (COMPLETED).
 * - Hàng giao không được / giao thiếu vẫn thuộc trách nhiệm của chuyến, chỉ nhập lại kho khi kết thúc chuyến.
 * - Quá expectedReturnAt mà chưa kết thúc -> cảnh báo trễ.
 */
@Entity
@Table(name = "delivery_trips", indexes = {
        @Index(name = "idx_delivery_trip_status", columnList = "status"),
        @Index(name = "idx_delivery_trip_warehouse", columnList = "warehouse_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DeliveryTrip {

    public static final String STATUS_IN_PROGRESS = "IN_PROGRESS";
    public static final String STATUS_COMPLETED = "COMPLETED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30, updatable = false)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Column(name = "vehicle_plate", length = 20)
    private String vehiclePlate;

    @Column(name = "driver_name", nullable = false, length = 100)
    private String driverName;

    @Column(name = "driver_phone", length = 20)
    private String driverPhone;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = STATUS_IN_PROGRESS;

    @Column(name = "departed_at", nullable = false)
    private LocalDateTime departedAt;

    /** Thời điểm dự kiến xong chuyến (giao hết và về kho); quá giờ này mà chưa kết thúc -> trễ. */
    @Column(name = "expected_return_at", nullable = false)
    private LocalDateTime expectedReturnAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "completed_by_username", length = 50)
    private String completedByUsername;

    @Column(length = 500)
    private String note;

    @Column(name = "created_by_username", length = 50, updatable = false)
    private String createdByUsername;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean isInProgress() {
        return STATUS_IN_PROGRESS.equals(status);
    }
}
