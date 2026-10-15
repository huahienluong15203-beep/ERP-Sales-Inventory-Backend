package com.erp.backend.dto.trip;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** S6-07: Một điểm giao (đơn hàng) trên chuyến. stockReturned = phần giao không được đã nhập lại kho. */
public record DeliveryTripStopResponse(
        Long orderId,
        String orderCode,
        Long customerId,
        String customerCode,
        String customerName,
        String deliveryAddress,
        String status,
        BigDecimal totalAmount,
        BigDecimal valueOnRoad,
        String receiverName,
        LocalDateTime deliveredAt,
        String deliveryFailedReason,
        boolean stockReturned) {
}
