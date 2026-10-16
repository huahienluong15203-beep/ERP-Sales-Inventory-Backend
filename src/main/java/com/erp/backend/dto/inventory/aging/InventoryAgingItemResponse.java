package com.erp.backend.dto.inventory.aging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S8-07: Chi tiết tuổi hàng (Aging) và vòng quay của từng SKU theo kho.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAgingItemResponse {
    private Long id;
    private Long productId;
    private String productSku;
    private String productName;
    private String categoryName;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String baseUnit;
    private BigDecimal quantity;
    private BigDecimal costPrice;
    private BigDecimal totalValue;
    private long averageAgingDays;
    private String agingBracket;   // 0_30, 31_60, 61_90, OVER_90
    private String turnoverSpeed;  // FAST, NORMAL, SLOW, STAGNANT
    private String lastMovementDate;
}
