package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * S2-06: Nhóm hàng dạng cây nhiều cấp.
 * Cấp 1: Ngành hàng (vd Đồ uống) → Cấp 2: Nhóm hàng (Nước ngọt) → Cấp 3: Phân nhóm (Có ga)...
 * - Mã nhóm là duy nhất, không đổi sau khi tạo.
 * - Nhóm còn nhóm con hoặc còn sản phẩm thì không xoá được.
 */
@Entity
@Table(name = "product_categories")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class ProductCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    // 1 = gốc (ngành hàng). Cấp của nhóm con = cấp của nhóm cha + 1.
    @Column(nullable = false)
    private Integer level;

    // null = nhóm gốc
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ProductCategory parent;

    @Column(length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
