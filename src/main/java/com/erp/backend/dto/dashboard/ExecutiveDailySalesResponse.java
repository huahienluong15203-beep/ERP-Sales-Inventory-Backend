package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-05: Biểu đồ doanh số theo ngày trong tháng.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveDailySalesResponse {
    private int month;
    private int year;
    private BigDecimal totalMonthSales;
    private long totalMonthOrders;
    private List<ExecutiveDailySalesPoint> points;
    private boolean fromCache;
}
