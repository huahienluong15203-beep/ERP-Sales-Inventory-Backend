package com.erp.backend.dto.creditnote;

import java.math.BigDecimal;

/** S8-02: Phần giảm trừ đối trừ vào 1 hoá đơn. */
public record CreditNoteAllocationResponse(
        Long invoiceId,
        String invoiceCode,
        BigDecimal amount,
        BigDecimal remainingBefore,
        BigDecimal remainingAfter) {
}
