package com.erp.backend.dto.inventory.aging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S8-07: Tổng hợp các chỉ số cảnh báo hàng chậm luân chuyển chôn vốn.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAgingSummaryResponse {
    private BigDecimal totalInventoryValue;
    private int slowMovingCount;
    private double slowMovingRatio;
    private BigDecimal stagnantValue;
    private double averageSystemDays;
}
