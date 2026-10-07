package com.erp.backend.dto.pricing;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PriceListItemResponse(
        Long id,
        Long productId,
        String productSku,
        String productName,
        BigDecimal price,
        BigDecimal floorPrice,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
