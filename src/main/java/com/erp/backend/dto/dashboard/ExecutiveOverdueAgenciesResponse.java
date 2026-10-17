package com.erp.backend.dto.dashboard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-05: Danh sách đại lý quá hạn công nợ, xếp theo số tiền nợ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutiveOverdueAgenciesResponse {
    private BigDecimal totalOverdueAmount;
    private int totalOverdueAgencies;
    private List<ExecutiveOverdueAgencyItem> agencies;
    private boolean fromCache;
}
