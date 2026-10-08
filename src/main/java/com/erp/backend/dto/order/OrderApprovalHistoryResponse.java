package com.erp.backend.dto.order;

import java.time.LocalDateTime;

/** S4-05: Một bước trong lịch sử chốt / duyệt đơn. */
public record OrderApprovalHistoryResponse(
        Long id,
        String action,
        String actionLabel,
        String fromStatus,
        String toStatus,
        String comment,
        String actorUsername,
        String actorFullName,
        LocalDateTime createdAt) {
}
