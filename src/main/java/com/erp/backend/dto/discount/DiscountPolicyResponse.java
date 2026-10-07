package com.erp.backend.dto.discount;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DiscountPolicyResponse(
        Long id,
        String code,
        String name,
        String scope,
        Long productId,
        String productSku,
        String productName,
        Long categoryId,
        String categoryName,
        String discountType,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String note,
        List<DiscountTierResponse> tiers,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
