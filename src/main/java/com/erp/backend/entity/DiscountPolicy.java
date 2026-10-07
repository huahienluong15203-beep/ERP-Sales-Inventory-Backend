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
 * S3-01: Chính sách chiết khấu theo sản lượng.
 * - Áp cho một SKU (scope = PRODUCT) hoặc một nhóm hàng kèm các nhóm con (scope = CATEGORY).
 * - Chiết khấu theo phần trăm (PERCENT) hoặc số tiền trên mỗi đơn vị cơ sở (AMOUNT_PER_UNIT).
 * - Nhiều bậc: mua từ minQuantity trở lên thì được discountValue của bậc đó.
 * - Không xoá cứng: ngừng áp dụng bằng status = INACTIVE.
 */
@Entity
@Table(name = "discount_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DiscountPolicy {

    public static final String SCOPE_PRODUCT = "PRODUCT";
    public static final String SCOPE_CATEGORY = "CATEGORY";
    public static final String TYPE_PERCENT = "PERCENT";
    public static final String TYPE_AMOUNT = "AMOUNT_PER_UNIT";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    // PRODUCT | CATEGORY
    @Column(nullable = false, length = 20)
    private String scope;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    // PERCENT | AMOUNT_PER_UNIT
    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    // DEALER_LEVEL_1 | DEALER_LEVEL_2 | RETAIL | null (áp dụng cho tất cả nhóm đại lý)
    @Enumerated(EnumType.STRING)
    @Column(name = "customer_group", length = 30)
    private CustomerGroup customerGroup;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    // ACTIVE | INACTIVE
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(length = 500)
    private String note;

    @OneToMany(mappedBy = "policy", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("minQuantity ASC")
    @Builder.Default
    private List<DiscountTier> tiers = new ArrayList<>();

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void addTier(DiscountTier tier) {
        tier.setPolicy(this);
        tiers.add(tier);
    }
}
