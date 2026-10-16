package com.erp.backend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S7-02: Phản hồi thông tin phiếu thu và cấn trừ công nợ. */
public record PaymentReceiptResponse(
        Long id,
        String code,
        Long customerId,
        String customerCode,
        String customerName,
        String customerAddress,
        String customerPhone,
        BigDecimal amount,
        String paymentMethod,
        LocalDate paymentDate,
        String collectorName,
        String collectorUsername,
        Long collectorId,
        String notes,
        String status,
        String receiptImageUrl,
        LocalDateTime confirmedAt,
        String confirmedByUsername,
        String allocationType,
        List<PaymentAllocationResponse> allocations,
        LocalDateTime createdAt,
        String createdByUsername) {
}
