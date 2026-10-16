package com.erp.backend.dto.notification;

import java.time.LocalDateTime;

/** S7-08: Phản hồi thông tin thông báo trong ứng dụng. */
public record NotificationResponse(
        Long id,
        String title,
        String content,
        String notificationType,
        String referenceType,
        Long referenceId,
        String referenceCode,
        boolean isRead,
        LocalDateTime readAt,
        LocalDateTime createdAt) {
}
