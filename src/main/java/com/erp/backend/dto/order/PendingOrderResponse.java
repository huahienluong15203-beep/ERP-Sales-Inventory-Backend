package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S4-05: Một dòng trong danh sách đơn chờ duyệt, kèm lý do cần duyệt, mức vi phạm và ngày mong muốn nhận. */
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
        List<ApprovalReason> reasons,
        LocalDate desiredDeliveryDate) {

    public PendingOrderResponse(
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
        this(id, code, customerId, customerCode, customerName, lineCount, totalAmount, submittedByUsername, submittedAt, reasons, null);
    }
}
