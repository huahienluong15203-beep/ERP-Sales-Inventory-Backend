package com.erp.backend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** S7-05: Một khoản thu chi tiết trong bảng đối chiếu thu tiền. */
public record PaymentReconciliationItemResponse(
        Long id,
        String code,
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal amount,
        String paymentMethod,
        LocalDate paymentDate,
        String notes,
        String receiptImageUrl,
        String status,
        LocalDateTime createdAt,
        LocalDateTime confirmedAt,
        String confirmedByUsername,
        LocalDateTime rejectedAt,
        String rejectedByUsername,
        String rejectionReason) {
}
