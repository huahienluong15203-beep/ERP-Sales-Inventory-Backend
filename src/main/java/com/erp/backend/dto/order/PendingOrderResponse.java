package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** S4-05: Một dòng trong danh sách đơn chờ duyệt, kèm lý do cần duyệt và mức vi phạm. */
public record PendingOrderResponse(
        Long id,
        String code,
        Long customerId,
        String customerCode,
        String customerName,
        int lineCount,
        BigDecimal totalAmount,
        String submittedByUsername,
        LocalDateTime submittedAt,
        List<ApprovalReason> reasons) {
}
