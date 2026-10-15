package com.erp.backend.dto.invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** S7-01: Một dòng trong danh sách hoá đơn. */
public record InvoiceSummaryResponse(
        Long id,
        String code,
        LocalDate invoiceDate,
        String status,
        Long customerId,
        String customerCode,
        String customerName,
        int lineCount,
        BigDecimal totalAmount,
        LocalDateTime issuedAt,
        String issuedByUsername) {
}
