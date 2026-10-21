package com.erp.backend.dto.portal;

import java.math.BigDecimal;
import java.util.List;

/**
 * S4-10: Sản phẩm đại lý xem trên cổng: chỉ giá theo bảng giá nhóm khách của đại lý.
 * Không có giá sàn, giá vốn, tồn thực tế / giữ chỗ nội bộ — chỉ tồn khả dụng để biết còn hàng.
 *
 * @param unitPrice giá 1 ĐVT cơ sở (giá 1 ĐVT khác = unitPrice × hệ số)
 */
public record PortalProductResponse(
        Long productId,
        String sku,
        String name,
        String baseUnit,
        List<Unit> units,
        boolean priceAvailable,
        BigDecimal unitPrice,
        String message,
        BigDecimal availableStock) {

    public record Unit(String unitName, BigDecimal conversionFactor) {
    }
}
