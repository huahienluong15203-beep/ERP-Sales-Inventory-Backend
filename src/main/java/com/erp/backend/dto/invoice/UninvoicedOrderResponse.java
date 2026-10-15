package com.erp.backend.dto.invoice;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** S7-01: Đơn đã giao chưa lập hoá đơn (để chọn gộp), kèm tiền theo số thực giao. */
public record UninvoicedOrderResponse(
        Long orderId,
        String orderCode,
        String status,
        LocalDateTime deliveredAt,
        int lineCount,
        BigDecimal orderTotalAmount,
        BigDecimal deliveredAmount) {
}
