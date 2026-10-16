package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.util.List;

/**
 * S8-09: Một đại lý có nợ quá hạn, xếp theo mức rủi ro.
 * riskLevel: HIGH (có nợ trên 90 ngày hoặc còn nợ vượt hạn mức) / MEDIUM (có nợ quá hạn).
 * reasons: lý do rủi ro để kế toán đọc nhanh.
 */
public record OverdueRiskRow(
        int rank,
        Long customerId,
        String customerCode,
        String customerName,
        Long salesRepId,
        String salesRepName,
        String riskLevel,
        List<String> reasons,
        BigDecimal creditLimit,
        BigDecimal outstanding,
        BigDecimal overdueAmount,
        BigDecimal over90,
        long oldestDebtDays) {
}
