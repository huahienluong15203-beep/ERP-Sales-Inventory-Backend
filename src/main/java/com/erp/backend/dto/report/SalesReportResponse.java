package com.erp.backend.dto.report;

import java.time.LocalDate;
import java.util.List;

/** S8-06: Báo cáo doanh số (bản không có biên lợi nhuận). compare: PREVIOUS | LAST_YEAR | NONE. */
public record SalesReportResponse(
        String groupBy,
        LocalDate fromDate,
        LocalDate toDate,
        String compare,
        LocalDate compareFromDate,
        LocalDate compareToDate,
        boolean marginVisible,
        List<SalesReportRow> rows,
        SalesReportRow total) {
}
