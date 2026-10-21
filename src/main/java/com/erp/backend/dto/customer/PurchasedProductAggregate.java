package com.erp.backend.dto.customer;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S4-04: Kết quả tổng hợp trong DB (group by sản phẩm) các dòng đơn đã mua của một đại lý.
 * Dùng cho câu "select new" trong SalesOrderRepository, không trả ra API.
 *
 * @param totalBaseQuantity tổng số lượng quy về ĐVT cơ sở
 * @param orderCount        số đơn khác nhau có sản phẩm này
 * @param lastOrderedAt     thời điểm mua gần nhất (approvedAt, thiếu thì createdAt)
 */
public record PurchasedProductAggregate(
        Long productId,
        BigDecimal totalBaseQuantity,
        Long orderCount,
        LocalDateTime lastOrderedAt
) {
}
