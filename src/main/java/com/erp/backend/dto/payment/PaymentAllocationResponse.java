package com.erp.backend.dto.payment;

import java.math.BigDecimal;

/** S7-02: Chi tiết một dòng cấn trừ hoá đơn. */
public record PaymentAllocationResponse(
        Long invoiceId,
        String invoiceCode,
        String orderCode,
        BigDecimal allocatedAmount,
        BigDecimal invoiceTotal,
        BigDecimal remainingBefore,
        BigDecimal remainingAfter) {
}
