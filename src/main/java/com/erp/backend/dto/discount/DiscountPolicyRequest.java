package com.erp.backend.dto.discount;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * S3-01: Tạo / sửa chính sách chiết khấu.
 * scope = PRODUCT thì gửi productSku; scope = CATEGORY thì gửi categoryId.
 * discountType = PERCENT hoặc AMOUNT_PER_UNIT.
 */
@Getter
@Setter
public class DiscountPolicyRequest {
    private String code;
    private String name;
    private String scope;
    private String productSku;
    private Long categoryId;
    private String discountType;
    private String customerGroup;
    private LocalDate startDate;
    private LocalDate endDate;
    private String note;
    private List<DiscountTierRequest> tiers;
}
