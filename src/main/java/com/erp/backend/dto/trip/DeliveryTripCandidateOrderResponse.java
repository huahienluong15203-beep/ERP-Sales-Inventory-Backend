package com.erp.backend.dto.trip;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S6-06: Đơn hàng đã xuất kho (DISPATCHED) sẵn sàng gom chuyến theo khu vực (AC1).
 */
public record DeliveryTripCandidateOrderResponse(
        Long orderId,
        String orderCode,
        Long customerId,
        String customerCode,
        String customerName,
        Long regionId,
        String regionName,
        String district,
        String province,
        String deliveryAddress,
        String receiverName,
        String receiverPhone,
        BigDecimal totalAmount,
        String status,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        LocalDate desiredDeliveryDate,
        LocalDateTime dispatchedAt,
        String dispatchedByUsername) {
}
