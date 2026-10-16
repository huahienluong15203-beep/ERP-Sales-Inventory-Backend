package com.erp.backend.dto.creditnote;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** S8-02: Phiếu trả hàng đã nhập kho, chờ kế toán lập chứng từ giảm trừ. creditAmount = số sẽ giảm trừ. */
public record PendingCreditReturnResponse(
        Long salesReturnId,
        String salesReturnCode,
        Long invoiceId,
        String invoiceCode,
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal creditAmount,
        LocalDateTime receivedAt) {
}
