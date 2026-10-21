package com.erp.backend.dto.creditnote;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S8-02: Chứng từ giảm trừ công nợ. */
public record CreditNoteResponse(
        Long id,
        String code,
        Long salesReturnId,
        String salesReturnCode,
        Long invoiceId,
        String invoiceCode,
        Long customerId,
        String customerCode,
        String customerName,
        LocalDate noteDate,
        BigDecimal amount,
        BigDecimal appliedAmount,
        BigDecimal unappliedAmount,
        String status,
        String returnReason,
        String note,
        LocalDateTime createdAt,
        String createdByUsername,
        List<CreditNoteLineResponse> lines,
        List<CreditNoteAllocationResponse> allocations) {
}
