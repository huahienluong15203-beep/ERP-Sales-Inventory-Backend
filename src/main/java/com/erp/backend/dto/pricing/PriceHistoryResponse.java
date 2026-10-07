package com.erp.backend.dto.pricing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** S3-02: Một lần thay đổi giá: giá cũ, giá mới, người sửa, thời điểm, ngày áp dụng. */
public record PriceHistoryResponse(
        Long id,
        Long priceListId,
        String priceListCode,
        String customerGroup,
        String customerGroupLabel,
        Long productId,
        String productSku,
        String productName,
        String changeType,
        String changeTypeLabel,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        BigDecimal oldFloorPrice,
        BigDecimal newFloorPrice,
        LocalDate effectiveDate,
        String changedByUsername,
        String changedByName,
        LocalDateTime changedAt) {
}
