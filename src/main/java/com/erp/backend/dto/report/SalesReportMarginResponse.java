package com.erp.backend.dto.report;

import java.time.LocalDate;
import java.util.List;

/** S8-06: Báo cáo doanh số kèm biên lợi nhuận gộp - chỉ Quản lý kinh doanh. */
public record SalesReportMarginResponse(
        String groupBy,
        LocalDate fromDate,
        LocalDate toDate,
        String compare,
        LocalDate compareFromDate,
        LocalDate compareToDate,
        boolean marginVisible,
        List<SalesReportMarginRow> rows,
        SalesReportMarginRow total) {
}
