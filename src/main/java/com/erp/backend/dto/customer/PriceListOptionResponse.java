package com.erp.backend.dto.customer;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Thông tin bảng giá áp dụng thực tế")
public record PriceListOptionResponse(
        Long id,
        String code,
        String name,
        String customerGroup,
        String customerGroupLabel
) {}
