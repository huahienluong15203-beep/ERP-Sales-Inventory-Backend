package com.erp.backend.dto.audit;

import java.time.LocalDateTime;

/**
 * S2-04: Dữ liệu phản hồi nhật ký thao tác hệ thống cho Frontend / Client.
 */
public record AuditLogResponse(
        Long id,
        String module,
        String moduleLabel,
        String action,
        String targetType,
        Long targetId,
        String targetCode,
        Long actorId,
        String actorUsername,
        String actorFullName,
        String actorAvatarUrl,
        String oldValue,
        String newValue,
        String reason,
        String ipAddress,
        String userAgent,
        String httpMethod,
        String requestUri,
        LocalDateTime createdAt) {
}
