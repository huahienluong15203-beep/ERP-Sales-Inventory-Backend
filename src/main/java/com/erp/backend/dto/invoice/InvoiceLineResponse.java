package com.erp.backend.dto.invoice;

import java.math.BigDecimal;

/** S7-01: Dòng hoá đơn (số lượng = số thực giao theo ĐVT của dòng đơn). */
public record InvoiceLineResponse(
        Integer lineNo,
        Long orderId,
        String orderCode,
        Long orderLineId,
        Long productId,
        String productSku,
        String productName,
        String unitName,
        BigDecimal quantity,
        String baseUnit,
        BigDecimal baseQuantity,
        BigDecimal pricePerUnit,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount) {
}
