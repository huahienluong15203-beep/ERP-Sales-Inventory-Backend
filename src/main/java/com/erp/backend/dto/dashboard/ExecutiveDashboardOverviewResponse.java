package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * S8-05: Dữ liệu toàn diện Dashboard điều hành bán hàng và kho.
 * Phục vụ Quản lý kinh doanh nắm bắt toàn diện mỗi sáng trong 2 phút.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveDashboardOverviewResponse {
    private ExecutiveDashboardSummaryResponse summary;
    private ExecutiveDailySalesResponse dailySales;
    private ExecutiveRegionSalesResponse regionSales;
    private ExecutiveOverdueAgenciesResponse overdueAgencies;
    private long responseTimeMs;
    private boolean fromCache;
    private LocalDateTime timestamp;
}
