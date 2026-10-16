package com.erp.backend.dto.creditnote;

import java.math.BigDecimal;

/** S8-02: Dòng giảm trừ (đơn giá = thành tiền dòng hoá đơn gốc / số lượng trên hoá đơn). */
public record CreditNoteLineResponse(
        Integer lineNo,
        Long invoiceLineId,
        String productSku,
        String productName,
        String unitName,
        BigDecimal returnQuantity,
        BigDecimal invoiceQuantity,
        BigDecimal invoiceNetAmount,
        BigDecimal amount) {
}
