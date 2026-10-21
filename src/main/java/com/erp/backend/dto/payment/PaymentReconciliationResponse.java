package com.erp.backend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * S7-05: Bảng đối chiếu tiền thu theo từng nhân viên và từng ngày (hoặc kỳ).
 * Đối chiếu tiền đã thu với tiền đã nộp mà không tranh cãi.
 */
public record PaymentReconciliationResponse(
        LocalDate fromDate,
        LocalDate toDate,
        Long collectorId,
        String collectorName,
        String collectorUsername,
        BigDecimal totalCollectedAmount,
        BigDecimal pendingAmount,
        BigDecimal confirmedAmount,
        BigDecimal rejectedAmount,
        int totalCount,
        int pendingCount,
        int confirmedCount,
        int rejectedCount,
        List<PaymentReconciliationItemResponse> items) {
}
