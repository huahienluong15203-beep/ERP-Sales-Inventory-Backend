package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * S3-09 / S4-01 / S4-03: Sản phẩm gợi ý khi gõ mã / tên để thêm dòng hàng, kèm đơn vị tính, giá niêm yết, giá sàn và tồn khả dụng theo kho.
 * priceAvailable = false thì không thêm được dòng hàng (message ghi lý do).
 */
public record ProductOptionResponse(
        Long productId,
        String sku,
        String name,
        String baseUnit,
        List<UnitOption> units,
        boolean priceAvailable,
        BigDecimal unitPrice,
        BigDecimal floorPrice,
        String priceListCode,
        String message,
        String warehouseCode,
        String warehouseName,
        BigDecimal physicalStock,
        BigDecimal reservedStock,
        BigDecimal availableStock) {

    public record UnitOption(String unitName, BigDecimal conversionFactor) {
    }

    public ProductOptionResponse(Long productId, String sku, String name, String baseUnit,
                                 List<UnitOption> units, boolean priceAvailable, BigDecimal unitPrice,
                                 BigDecimal floorPrice, String priceListCode, String message) {
        this(productId, sku, name, baseUnit, units, priceAvailable, unitPrice, floorPrice, priceListCode, message,
                null, null, null, null, null);
    }

    public ProductOptionResponse(Long productId, String sku, String name, String baseUnit,
                                 List<UnitOption> units, boolean priceAvailable, BigDecimal unitPrice,
                                 String priceListCode, String message) {
        this(productId, sku, name, baseUnit, units, priceAvailable, unitPrice, null, priceListCode, message,
                null, null, null, null, null);
    }
}
