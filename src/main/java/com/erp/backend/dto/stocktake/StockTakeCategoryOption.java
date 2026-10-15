package com.erp.backend.dto.stocktake;

/**
 * DTO trả về danh sách nhóm hàng có mặt hàng thực tế trong kho kèm số lượng SKU (S5-08).
 */
public record StockTakeCategoryOption(
        Long id,
        String code,
        String name,
        Integer level,
        Long parentId,
        String parentName,
        String description,
        Long skuCount
) {
}
