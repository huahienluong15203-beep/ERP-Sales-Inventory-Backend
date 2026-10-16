package com.erp.backend.dto.inventory.aging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S8-07: Giá trị tồn kho theo từng kho lưu trữ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAgingWarehouseValueResponse {
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private long skuCount;
    private BigDecimal totalQuantity;
    private BigDecimal totalValue;
}
