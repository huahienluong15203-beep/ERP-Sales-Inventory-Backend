package com.erp.backend.dto.inventory;

import java.math.BigDecimal;

/**
 * S4-03 / SCRUM-154: Thông tin tồn kho của sản phẩm tại kho phục vụ.
 */
public record StockInfoDto(
        String warehouseCode,
        String warehouseName,
        BigDecimal physicalStock,
        BigDecimal reservedStock,
        BigDecimal availableStock
) {
    public static StockInfoDto of(String warehouseCode, String warehouseName,
                                  BigDecimal physicalStock, BigDecimal reservedStock, BigDecimal availableStock) {
        return new StockInfoDto(
                warehouseCode,
                warehouseName,
                physicalStock != null ? physicalStock : BigDecimal.ZERO,
                reservedStock != null ? reservedStock : BigDecimal.ZERO,
                availableStock != null ? availableStock : BigDecimal.ZERO
        );
    }
}
