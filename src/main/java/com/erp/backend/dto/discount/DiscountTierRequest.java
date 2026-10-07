package com.erp.backend.dto.discount;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class DiscountTierRequest {
    private BigDecimal minQuantity;
    private BigDecimal discountValue;
}
