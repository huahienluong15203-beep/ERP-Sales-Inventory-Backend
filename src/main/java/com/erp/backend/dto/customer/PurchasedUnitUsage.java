package com.erp.backend.dto.customer;

import java.math.BigDecimal;

/**
 * S4-04: Số dòng đơn đã đặt theo từng ĐVT của một sản phẩm (để chọn ĐVT đại lý hay dùng nhất).
 * Dùng cho câu "select new" trong SalesOrderRepository, không trả ra API.
 */
public record PurchasedUnitUsage(
        Long productId,
        String unitName,
        BigDecimal conversionFactor,
        Long lineCount,
        BigDecimal totalBaseQuantity
) {
}
