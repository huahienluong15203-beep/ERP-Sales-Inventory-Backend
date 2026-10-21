package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S8-05: Thẻ chỉ số điều hành bán hàng và kho.
 * - Doanh số hôm nay và tháng này
 * - Đơn chờ duyệt và đơn chờ xuất
 * - Công nợ quá hạn
 * - Số SKU dưới tồn tối thiểu
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveDashboardSummaryResponse {
    private BigDecimal revenueToday;
    private BigDecimal revenueThisMonth;
    private long pendingApprovalOrders;
    private long pendingDispatchOrders;
    private BigDecimal overdueDebtAmount;
    private long skusBelowMinStock;
    private boolean fromCache;
    private LocalDateTime computedAt;
}
