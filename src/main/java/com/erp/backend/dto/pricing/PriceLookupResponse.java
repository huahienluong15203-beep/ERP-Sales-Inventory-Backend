package com.erp.backend.dto.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;

/** S2-10: Giá đang áp dụng cho một sản phẩm, theo nhóm khách hàng và ngày. */
public record PriceLookupResponse(
        Long priceListId,
        String priceListCode,
        String priceListName,
        String customerGroup,
        String customerGroupLabel,
        LocalDate effectiveDate,
        Long productId,
        String productSku,
        String productName,
        BigDecimal price,
        BigDecimal floorPrice) {
}
