package com.erp.backend.dto.inventory;

import java.util.Collection;

/**
 * S5-05: Bộ lọc sổ tồn.
 *
 * @param warehouseId          kho cần xem (null = mọi kho được phép)
 * @param allowedWarehouseIds  giới hạn kho theo người dùng (null = không giới hạn; rỗng = không được xem kho nào)
 * @param categoryIds          nhóm hàng đã chọn kèm toàn bộ nhóm con (null = mọi nhóm)
 * @param stockStatus          IN_STOCK | FULLY_RESERVED | OUT_OF_STOCK (null = tất cả)
 * @param keyword              mã hoặc tên sản phẩm
 */
public record StockLedgerCriteria(
        Long warehouseId,
        Collection<Long> allowedWarehouseIds,
        Collection<Long> categoryIds,
        String stockStatus,
        String keyword) {
}
