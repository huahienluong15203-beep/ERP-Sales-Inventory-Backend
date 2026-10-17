package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * S8-10 / S7-06: Một dòng phát sinh công nợ của đại lý.
 * type: INVOICE (hoá đơn, tăng nợ) · PAYMENT (phiếu thu, giảm nợ) · ADJUSTMENT (giảm trừ do trả hàng, giảm nợ).
 * balance: số dư luỹ kế sau dòng này.
 */
public record DebtStatementEntry(
        Long refId,
        LocalDate date,
        String type,
        String typeLabel,
        String code,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance) {
}
