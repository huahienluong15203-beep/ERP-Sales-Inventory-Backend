package com.erp.backend.dto.credit;

import java.time.LocalDateTime;

/** S7-04: Đại lý đang bị gắn cờ công nợ. */
public record FlaggedCustomerResponse(
        Long customerId,
        String customerCode,
        String customerName,
        String salesRepName,
        String reason,
        LocalDateTime flaggedAt,
        LocalDateTime checkedAt) {
}
