package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-05: Biểu đồ doanh số theo khu vực.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveRegionSalesResponse {
    private int month;
    private int year;
    private BigDecimal totalSales;
    private List<ExecutiveRegionSalesItem> regions;
    private boolean fromCache;
}
