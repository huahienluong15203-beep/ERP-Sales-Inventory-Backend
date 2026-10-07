package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S3-04: Điểm giao hàng của đại lý (một đại lý có nhiều điểm giao).
 * - Mỗi đại lý luôn có đúng 1 điểm giao mặc định (nếu còn điểm giao đang dùng).
 * - Không xoá cứng (đơn hàng sau này sẽ tham chiếu tới): chỉ chuyển INACTIVE.
 */
@Entity
@Table(name = "customer_delivery_addresses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class CustomerDeliveryAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    // Tên gợi nhớ, vd: "Kho chính", "Cửa hàng số 2"
    @Column(length = 100)
    private String label;

    @Column(nullable = false, length = 500)
    private String address;

    @Column(name = "receiver_name", nullable = false, length = 100)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false, length = 20)
    private String receiverPhone;

    // Ghi chú đường đi
    @Column(length = 500)
    private String note;

    @Column(name = "is_default", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean defaultAddress = false;

    // ACTIVE | INACTIVE
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
