package com.erp.backend.dto.portal;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S7-07: Công nợ của chính đại lý trên cổng đại lý.
 * - currentDebt = invoicedDebt (còn nợ trên hoá đơn, đã trừ tiền thu) + uninvoicedDebt (đơn đã duyệt chưa xuất hoá đơn).
 * - availableCredit = hạn mức - currentDebt (âm = đã vượt hạn mức).
 * - nextDueDate / nextDueAmount: hoá đơn còn nợ sắp đến hạn gần nhất (chưa quá hạn).
 */
public record PortalDebtSummaryResponse(
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal creditLimit,
        BigDecimal currentDebt,
        BigDecimal availableCredit,
        BigDecimal invoicedDebt,
        BigDecimal uninvoicedDebt,
        Integer maxDebtDays,
        int openInvoiceCount,
        boolean overdue,
        long overdueCount,
        BigDecimal overdueAmount,
        long overdueDays,
        LocalDate nextDueDate,
        BigDecimal nextDueAmount,
        String message) {
}
