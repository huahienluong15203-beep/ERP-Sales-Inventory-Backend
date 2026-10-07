package com.erp.backend.dto.pricing;

/** S2-10: Số liệu tổng hợp cho các thẻ thống kê trên đầu trang bảng giá (tính trên toàn bộ dữ liệu, không theo trang). */
public record PriceListStatsResponse(
        long total,
        long active,
        long dealerLevel1,
        long dealerLevel2,
        long retail,
        long locked) {
}
