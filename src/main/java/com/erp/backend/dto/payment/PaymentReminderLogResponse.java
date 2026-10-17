package com.erp.backend.dto.payment;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** S7-08: Nhật ký gửi nhắc hạn thanh toán (DTO, cùng tên trường như trước để FE không phải sửa). */
public record PaymentReminderLogResponse(
        Long id,
        Long invoiceId,
        String invoiceCode,
        Long customerId,
        String customerCode,
        String customerName,
        String reminderType,
        LocalDate reminderDate,
        LocalDate dueDate,
        Long daysDifference,
        boolean emailSent,
        boolean inAppSent,
        String recipientEmail,
        LocalDateTime sentAt) {
}
