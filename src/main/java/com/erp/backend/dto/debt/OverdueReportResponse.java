package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * S8-09: Báo cáo công nợ quá hạn.
 * - byBucket: theo nhóm tuổi nợ; byRep: theo NV phụ trách (nợ quá hạn nhiều nhất trước).
 * - topRisks: đại lý rủi ro cao nhất trước (giới hạn theo tham số top); customers: toàn bộ đại lý nợ quá hạn đã xếp hạng.
 */
public record OverdueReportResponse(
        LocalDate asOf,
        int overdueCustomerCount,
        int highRiskCount,
        BigDecimal totalOutstanding,
        BigDecimal totalOverdue,
        List<OverdueBucketSummary> byBucket,
        List<OverdueRepSummary> byRep,
        List<OverdueRiskRow> topRisks,
        List<OverdueRiskRow> customers) {
}
