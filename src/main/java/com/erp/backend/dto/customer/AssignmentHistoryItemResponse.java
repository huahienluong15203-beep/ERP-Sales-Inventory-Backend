package com.erp.backend.dto.customer;

import com.erp.backend.dto.user.RefItem;

import java.time.LocalDateTime;

/**
 * S3-06: Chi tiết lịch sử phân công / chuyển giao địa bàn toàn hệ thống.
 * Phục vụ màn hình Nhật ký hệ thống và truy vết kiểm toán.
 */
public record AssignmentHistoryItemResponse(
        Long id,
        String changeType,
        RefItem customer,
        RefItem region,
        RefItem fromSalesRep,
        RefItem toSalesRep,
        RefItem changedBy,
        String reason,
        LocalDateTime changedAt) {
}
