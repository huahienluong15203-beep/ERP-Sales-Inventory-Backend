package com.erp.backend.dto.discount;

import java.math.BigDecimal;

public record DiscountTierResponse(Long id, BigDecimal minQuantity, BigDecimal discountValue) {
}
