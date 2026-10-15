package com.erp.backend.dto.kpi;

import java.math.BigDecimal;

/**
 * S8-04: Tiến độ của chính NV kinh doanh trong tháng (cập nhật theo thời gian thực mỗi lần xem).
 * targetAmount = null nếu tháng này chưa được giao chỉ tiêu.
 * expectedToDateAmount: mức nên đạt đến hôm nay nếu chia đều chỉ tiêu theo ngày (để so tiến độ).
 */
public record MySalesProgressResponse(
        String period,
        BigDecimal targetAmount,
        BigDecimal actualAmount,
        BigDecimal achievementPercent,
        BigDecimal remainingAmount,
        int daysInMonth,
        int daysElapsed,
        BigDecimal expectedToDateAmount) {
}
