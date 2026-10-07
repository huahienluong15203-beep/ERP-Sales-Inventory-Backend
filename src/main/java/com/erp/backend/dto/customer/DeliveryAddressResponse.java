package com.erp.backend.dto.customer;

import java.time.LocalDateTime;

/** S3-04: Điểm giao hàng của đại lý. */
public record DeliveryAddressResponse(
        Long id,
        Long customerId,
        String label,
        String address,
        String receiverName,
        String receiverPhone,
        String note,
        boolean isDefault,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
