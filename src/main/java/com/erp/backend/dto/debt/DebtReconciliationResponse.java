package com.erp.backend.dto.debt;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S7-06: Biên bản đối chiếu công nợ (khớp kiểu PeriodicReconciliationStatement ở giao diện).
 * Xem trước (chưa lưu) thì id, statementCode, status = null.
 */
public record DebtReconciliationResponse(
        Long id,
        String statementCode,
        Long customerId,
        String customerCode,
        String customerName,
        String customerAddress,
        String customerTaxCode,
        String customerPhone,
        LocalDate fromDate,
        LocalDate toDate,
        LocalDate statementDate,
        BigDecimal openingBalance,
        BigDecimal totalDebit,
        BigDecimal totalCredit,
        BigDecimal closingBalance,
        String status,
        String statusLabel,
        String notes,
        String createdBy,
        LocalDateTime createdAt,
        String confirmedBy,
        LocalDateTime confirmedAt,
        String agencySignedBy,
        LocalDateTime agencySignedAt,
        String agencyFeedback,
        List<Transaction> transactions) {

    /** Dòng chứng từ trong kỳ; debit = phát sinh nợ (mua hàng), credit = phát sinh có (thanh toán / giảm trừ). */
    public record Transaction(Long id, LocalDate date, String code, String type, String typeLabel,
                              BigDecimal debit, BigDecimal credit, BigDecimal balance, String notes) {
    }
}
