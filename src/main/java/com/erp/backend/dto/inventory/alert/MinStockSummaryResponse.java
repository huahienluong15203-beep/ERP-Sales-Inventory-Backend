package com.erp.backend.dto.inventory.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Thống kê nhanh cảnh báo tồn kho cho Dashboard.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MinStockSummaryResponse {
    private long totalAlerts;
    private long criticalCount;
    private long warningCount;
    private List<MinStockAlertResponse> criticalItems;
    private long totalSkuCount;
    private long safeCount;
}
