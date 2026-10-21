package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S5-03 / SCRUM-161: Vị trí lưu trong kho (Khu vực / Kệ / Ô / Ngăn).
 * Để nhân viên kho biết chính xác hàng nằm ở kệ nào khi soạn đơn.
 */
@Entity
@Table(name = "warehouse_locations", uniqueConstraints = {
        @UniqueConstraint(name = "uk_warehouse_location_code", columnNames = {"warehouse_id", "code"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class WarehouseLocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    // Mã vị trí duy nhất trong phạm vi một kho (vd: ZONE-A, KE-01, RACK-B02, BIN-A1-03)
    @Column(nullable = false, length = 50)
    private String code;

    // Tên mô tả vị trí (vd: Khu A - Hàng tiêu dùng nhanh, Kệ 01 - Tầng 2)
    @Column(nullable = false, length = 150)
    private String name;

    // Loại vị trí: ZONE (Khu), RACK (Kệ), SHELF (Giá), BIN (Ô/Ngăn)
    @Column(name = "location_type", nullable = false, length = 30)
    @Builder.Default
    private String locationType = "RACK";

    // Tên phân khu chứa kệ này (nếu là Kệ thuộc Khu)
    @Column(length = 100)
    private String zone;

    // Mã/tên kệ
    @Column(length = 100)
    private String shelf;

    @Column(length = 500)
    private String description;

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
