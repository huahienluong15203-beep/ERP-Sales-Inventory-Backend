package com.erp.backend.dto.kpi;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S8-04: Chỉ tiêu kèm tiến độ thực tế (tính tại thời điểm xem).
 * achievementPercent: % hoàn thành (1 số lẻ); remainingAmount: còn thiếu (0 nếu đã vượt); locked: kỳ đã qua.
 */
public record SalesTargetResponse(
        Long id,
        String period,
        String targetType,
        Long salesRepId,
        String salesRepCode,
        String salesRepName,
        Long regionId,
        String regionName,
        BigDecimal targetAmount,
        BigDecimal actualAmount,
        BigDecimal achievementPercent,
        BigDecimal remainingAmount,
        boolean locked,
        String note,
        LocalDateTime updatedAt,
        String updatedByUsername) {

    public String getRegion() {
        return regionName;
    }

    public BigDecimal getCompletionRate() {
        return achievementPercent;
    }

    public boolean isLocked() {
        return locked;
    }
}
