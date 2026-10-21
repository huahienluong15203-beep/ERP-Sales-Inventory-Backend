package com.erp.backend.dto.inventory.stockcard;

import com.erp.backend.dto.user.PageResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S6-02: Thẻ kho trong khoảng thời gian, sắp theo thời gian tăng dần.
 * Số dư đầu kỳ / cuối kỳ, tổng nhập / xuất chỉ tính khi chọn đủ 1 kho + 1 sản phẩm (null nếu không).
 * openingBalance = null cũng có thể do SKU chưa có nghiệp vụ nào trước kỳ (dữ liệu trước khi có thẻ kho).
 */
public record StockCardResponse(
        Long warehouseId,
        Long productId,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,
        BigDecimal totalIn,
        BigDecimal totalOut,
        BigDecimal closingBalance,
        BigDecimal currentPhysicalStock,
        PageResponse<StockCardRow> movements) {
}
