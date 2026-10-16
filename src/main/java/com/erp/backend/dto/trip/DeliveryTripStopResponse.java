package com.erp.backend.dto.trip;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S6-06 / S6-07: Một điểm giao (đơn hàng) trên chuyến.
 * AC3: In danh sách điểm giao của chuyến kèm địa chỉ và số điện thoại người nhận.
 */
public record DeliveryTripStopResponse(
        Long orderId,
        String orderCode,
        Long customerId,
        String customerCode,
        String customerName,
        String deliveryAddress,
        String receiverName,
        String receiverPhone,
        String district,
        String province,
        Integer stopSequence,
        String status,
        BigDecimal totalAmount,
        BigDecimal valueOnRoad,
        LocalDateTime deliveredAt,
        String deliveryFailedReason,
        boolean stockReturned) {

    /** Constructor tương thích ngược cho các vị trí gọi kiểu cũ. */
    public DeliveryTripStopResponse(
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
        this(orderId, orderCode, customerId, customerCode, customerName, deliveryAddress,
                receiverName, null, null, null, null, status, totalAmount, valueOnRoad,
                deliveredAt, deliveryFailedReason, stockReturned);
    }
}
