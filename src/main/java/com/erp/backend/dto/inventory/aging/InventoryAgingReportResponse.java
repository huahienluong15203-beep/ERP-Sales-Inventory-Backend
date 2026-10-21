package com.erp.backend.dto.inventory.aging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S8-07: Payload báo cáo tồn kho và vòng quay hàng hóa (Aging Report).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryAgingReportResponse {
    private List<InventoryAgingItemResponse> items;
    private InventoryAgingSummaryResponse summary;
    private Integer page;
    private Integer size;
    private Long totalElements;
    private Integer totalPages;
}
