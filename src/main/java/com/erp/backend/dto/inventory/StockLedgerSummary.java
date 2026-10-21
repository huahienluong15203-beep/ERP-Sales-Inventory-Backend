package com.erp.backend.dto.inventory;

import java.math.BigDecimal;

/** S5-05: Tổng của toàn bộ kết quả đang lọc trên sổ tồn (không chỉ trang đang xem). */
public record StockLedgerSummary(
        long rowCount,
        BigDecimal totalPhysicalStock,
        BigDecimal totalReservedStock,
        BigDecimal totalAvailableStock,
        long inStockCount,
        long fullyReservedCount,
        long outOfStockCount) {
}
