package com.erp.backend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** S7-02: Danh sách hoá đơn còn nợ của đại lý để cấn trừ. */
public record CustomerInvoiceDebtResponse(
        Long id,
        String code,
        LocalDate invoiceDate,
        LocalDate dueDate,
        List<String> orderCodes,
        BigDecimal totalAmount,
        BigDecimal paidAmount,
        BigDecimal remainingAmount,
        String status,
        boolean isOverdue,
        long overdueDays) {
}
