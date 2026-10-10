package com.erp.backend.dto.inventory.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S5-09: Thống kê nhanh cảnh báo tồn kho cho Dashboard (AC2).
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
}
