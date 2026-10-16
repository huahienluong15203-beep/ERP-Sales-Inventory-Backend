package com.erp.backend.dto.payment;

import java.time.LocalDate;

/** S7-08: Kết quả chạy tác vụ quét nhắc hạn thanh toán định kỳ. */
public record PaymentReminderRunResponse(
        LocalDate runDate,
        int scannedCount,
        int sentBeforeDueCount,
        int sentOverdueCount,
        int skippedAlreadySentToday,
        int errorCount,
        String message) {
}
