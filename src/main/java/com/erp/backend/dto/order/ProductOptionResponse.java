package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * S3-09: Sản phẩm gợi ý khi gõ mã / tên để thêm dòng hàng, kèm đơn vị tính và giá của nhóm khách hàng.
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
        String priceListCode,
        String message) {

    public record UnitOption(String unitName, BigDecimal conversionFactor) {
    }
}
