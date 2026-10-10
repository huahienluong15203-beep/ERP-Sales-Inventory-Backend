package com.erp.backend.dto.stocktake;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S5-08: Phiếu kiểm kê kèm tổng hợp: số dòng, đã đếm, có chênh lệch, tổng thừa (+) / thiếu (-) theo ĐVT cơ sở.
 * Danh sách phiếu trả lines = rỗng để nhẹ.
 */
public record StockTakeResponse(
        Long id,
        String code,
        String status,
        String statusLabel,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        Long categoryId,
        String categoryName,
        String note,
        String createdByUsername,
        LocalDateTime createdAt,
        String closedByUsername,
        LocalDateTime closedAt,
        String closeReason,
        int lineCount,
        int countedCount,
        int differenceCount,
        BigDecimal totalSurplus,
        BigDecimal totalShortage,
        List<Line> lines) {

    public record Line(
            Long id,
            Long productId,
            String sku,
            String productName,
            String baseUnit,
            BigDecimal systemQuantity,
            BigDecimal countedQuantity,
            BigDecimal difference,
            String note) {
    }
}
