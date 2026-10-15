package com.erp.backend.dto.report;

import java.math.BigDecimal;

/**
 * S8-06: Một nhóm trong báo cáo doanh số KÈM biên lợi nhuận gộp - chỉ trả cho Quản lý kinh doanh (quy tắc 6).
 * costOfGoods = sản lượng x giá vốn hiện tại của SKU; grossMargin = revenue - costOfGoods; grossMarginPercent = % trên doanh số.
 */
public record SalesReportMarginRow(
        String key,
        Long id,
        String code,
        String name,
        BigDecimal revenue,
        BigDecimal quantity,
        long orderCount,
        BigDecimal previousRevenue,
        BigDecimal previousQuantity,
        BigDecimal revenueChangePercent,
        BigDecimal costOfGoods,
        BigDecimal grossMargin,
        BigDecimal grossMarginPercent,
        BigDecimal previousGrossMargin) {
}
