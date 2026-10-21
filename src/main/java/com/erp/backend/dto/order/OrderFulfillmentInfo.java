package com.erp.backend.dto.order;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S6-05 / S6-08: Thông tin xuất kho, đơn nợ hàng và giao hàng của đơn.
 *
 * @param backorder        đơn này là đơn nợ hàng (tách từ lần xuất thiếu của parentOrderCode)
 * @param backorderCodes   các đơn nợ hàng đã tách ra từ đơn này
 */
public record OrderFulfillmentInfo(
        LocalDateTime dispatchedAt,
        String dispatchedByUsername,
        String dispatchNote,
        boolean backorder,
        Long parentOrderId,
        String parentOrderCode,
        List<String> backorderCodes,
        // S6-08: giao hàng
        String receiverName,
        LocalDateTime deliveredAt,
        LocalDateTime deliveryConfirmedAt,
        String deliveryConfirmedByUsername,
        boolean hasDeliveryProof,
        String deliveryFailedReason,
        LocalDateTime deliveryFailedAt) {
}
