package com.erp.backend.dto.stockadjustment;

import lombok.*;

/**
 * S8-03: DTO phê duyệt hoặc từ chối phiếu điều chỉnh tồn kho.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentDecisionRequest {
    private String notes;
}
