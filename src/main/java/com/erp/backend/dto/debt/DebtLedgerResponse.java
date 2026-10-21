package com.erp.backend.dto.debt;

import java.time.LocalDate;
import java.util.List;

/** S7-03: Sổ công nợ & tuổi nợ (đã phân trang) kèm dòng tổng cộng. */
public record DebtLedgerResponse(
        LocalDate asOf,
        List<DebtAgingRow> rows,
        int page,
        int size,
        long totalElements,
        int totalPages,
        DebtAgingTotals totals) {
}
