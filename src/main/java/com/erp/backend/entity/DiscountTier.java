package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** S3-01: Một bậc chiết khấu: mua từ minQuantity (đơn vị cơ sở) trở lên thì được discountValue. */
@Entity
@Table(name = "discount_tiers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(of = "id")
public class DiscountTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "policy_id", nullable = false)
    private DiscountPolicy policy;

    // Số lượng tối thiểu theo đơn vị tính cơ sở của sản phẩm
    @Column(name = "min_quantity", nullable = false, precision = 15, scale = 4)
    private BigDecimal minQuantity;

    // % (0 < x <= 100) hoặc số tiền VND trên mỗi đơn vị cơ sở
    @Column(name = "discount_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountValue;
}
