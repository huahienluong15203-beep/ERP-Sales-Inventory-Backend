package com.erp.backend.dto.discount;

/** S3-01: Số liệu tổng hợp cho các thẻ thống kê trang chiết khấu (tính trên toàn bộ dữ liệu, không theo trang). */
public record DiscountPolicyStatsResponse(
        long total,
        long active,
        long productScope,
        long categoryScope) {
}
