package com.erp.backend.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S7-02 & S7-05: Phản hồi thông tin phiếu thu và cấn trừ công nợ. */
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
        LocalDateTime rejectedAt,
        String rejectedByUsername,
        String rejectionReason,
        String allocationType,
        List<PaymentAllocationResponse> allocations,
        LocalDateTime createdAt,
        String createdByUsername) {

    public PaymentReceiptResponse(
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
        this(id, code, customerId, customerCode, customerName, customerAddress, customerPhone,
                amount, paymentMethod, paymentDate, collectorName, collectorUsername, collectorId,
                notes, status, receiptImageUrl, confirmedAt, confirmedByUsername,
                null, null, null,
                allocationType, allocations, createdAt, createdByUsername);
    }
}
