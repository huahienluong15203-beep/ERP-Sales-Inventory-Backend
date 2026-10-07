package com.erp.backend.dto.category;

/** S2-06: Sản phẩm trong nhóm hàng (không có giá vốn). */
public record CategoryProductItem(
        Long id,
        String sku,
        String name,
        String baseUnit,
        String status,
        Long categoryId,
        String categoryName) {
}
