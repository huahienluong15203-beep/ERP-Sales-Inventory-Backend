package com.erp.backend.dto.inventory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S5-05: Một dòng sổ tồn kho 3 cột (theo ĐVT cơ sở): tồn thực tế, đang giữ chỗ, khả dụng = thực tế - giữ chỗ.
 * Không chứa giá vốn.
 */
public record StockLedgerRow(
        Long inventoryId,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        Long productId,
        String sku,
        String productName,
        String productStatus,
        Long categoryId,
        String categoryName,
        String baseUnit,
        BigDecimal physicalStock,
        BigDecimal reservedStock,
        BigDecimal availableStock,
        String stockStatus,
        String stockStatusLabel,
        LocalDateTime updatedAt) {
}
