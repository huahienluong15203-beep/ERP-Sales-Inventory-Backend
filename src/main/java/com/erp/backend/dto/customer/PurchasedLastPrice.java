package com.erp.backend.dto.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S4-04: Dòng đơn của lần mua gần nhất theo từng sản phẩm (để lấy đơn giá lần trước).
 * unitPrice: giá 1 ĐVT cơ sở; pricePerUnit: giá 1 unitName (có thể null ở dữ liệu cũ).
 * Dùng cho câu "select new" trong SalesOrderRepository, không trả ra API.
 */
public record PurchasedLastPrice(
        Long productId,
        Long orderId,
        Long lineId,
        LocalDateTime orderedAt,
        String unitName,
        BigDecimal conversionFactor,
        BigDecimal unitPrice,
        BigDecimal pricePerUnit
) {
}
