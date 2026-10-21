package com.erp.backend.dto.report;

import java.math.BigDecimal;

/**
 * S8-06: Một nhóm trong báo cáo doanh số (KHÔNG có giá vốn / biên lợi nhuận).
 * revenue = tiền hàng sau chiết khấu; quantity = sản lượng theo ĐVT cơ sở; revenueChangePercent = % tăng / giảm so với kỳ so sánh
 * (null nếu kỳ so sánh không có doanh số).
 */
public record SalesReportRow(
        String key,
        Long id,
        String code,
        String name,
        BigDecimal revenue,
        BigDecimal quantity,
        long orderCount,
        BigDecimal previousRevenue,
        BigDecimal previousQuantity,
        BigDecimal revenueChangePercent) {
}
