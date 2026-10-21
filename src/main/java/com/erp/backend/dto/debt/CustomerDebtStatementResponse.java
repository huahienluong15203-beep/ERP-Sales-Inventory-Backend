package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * S8-10: Sổ chi tiết công nợ của 1 đại lý trong kỳ.
 * Số dư cuối kỳ = Số dư đầu kỳ + Tổng phát sinh tăng - Tổng phát sinh giảm.
 */
public record CustomerDebtStatementResponse(
        Long customerId,
        String customerCode,
        String customerName,
        String customerAddress,
        String customerTaxCode,
        String customerPhone,
        LocalDate fromDate,
        LocalDate toDate,
        BigDecimal openingBalance,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance,
        List<DebtStatementEntry> entries) {
}
