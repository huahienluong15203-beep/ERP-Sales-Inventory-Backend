package com.erp.backend.dto.inventory.aging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S8-07: Giá trị tồn kho theo từng nhóm hàng (Category).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAgingCategoryValueResponse {
    private Long categoryId;
    private String categoryCode;
    private String categoryName;
    private long skuCount;
    private BigDecimal totalQuantity;
    private BigDecimal totalValue;
}
