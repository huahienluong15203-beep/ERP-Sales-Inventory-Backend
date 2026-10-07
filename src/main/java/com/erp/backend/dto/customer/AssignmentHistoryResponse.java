package com.erp.backend.dto.customer;

import com.erp.backend.dto.user.RefItem;

import java.time.LocalDateTime;

/** S3-06: Một dòng lịch sử phân công người phụ trách. */
public record AssignmentHistoryResponse(
        Long id,
        String changeType,
        RefItem fromSalesRep,
        RefItem toSalesRep,
        RefItem changedBy,
        String reason,
        LocalDateTime changedAt) {
}
