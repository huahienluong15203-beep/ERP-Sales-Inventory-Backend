package com.erp.backend.dto.pricing;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S2-10: Bảng giá trả về. items chỉ có khi xem chi tiết (danh sách chỉ trả itemsCount). */
public record PriceListResponse(
        Long id,
        String code,
        String name,
        String customerGroup,
        String customerGroupLabel,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        boolean hasOrders,
        Integer version,
        Long sourcePriceListId,
        String note,
        int itemsCount,
        List<PriceListItemResponse> items,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
