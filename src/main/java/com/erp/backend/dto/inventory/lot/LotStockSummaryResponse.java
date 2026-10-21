package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thống kê tổng quan tình trạng Lô & HSD (S6-01)")
public class LotStockSummaryResponse {

    private long totalLots;
    private BigDecimal totalQuantity;

    private long safeLots;
    private BigDecimal safeQuantity;

    private long nearExpiryLots;
    private BigDecimal nearExpiryQuantity;

    private long expiredLots;
    private BigDecimal expiredQuantity;

    private long outOfStockLots;
    private BigDecimal outOfStockQuantity;

    private int daysThresholdApplied;
}
