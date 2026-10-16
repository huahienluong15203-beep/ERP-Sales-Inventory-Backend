package com.erp.backend.dto.debt;

import java.math.BigDecimal;

/** S8-09: Tổng hợp nợ quá hạn theo nhân viên kinh doanh phụ trách. */
public record OverdueRepSummary(
        Long salesRepId,
        String salesRepName,
        int customerCount,
        int highRiskCount,
        BigDecimal outstanding,
        BigDecimal overdueAmount,
        BigDecimal d0To30,
        BigDecimal d31To60,
        BigDecimal d61To90,
        BigDecimal over90) {
}
