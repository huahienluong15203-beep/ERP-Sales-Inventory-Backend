package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Sản phẩm cho danh sách lựa chọn (combobox/searchable select)")
public record ProductOptionItemResponse(
        Long id,
        String sku,
        String name,
        String baseUnit,
        String packaging,
        String category,
        BigDecimal costPrice,
        String status
) {}
