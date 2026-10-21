package com.erp.backend.dto.trip;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S6-06 AC3: Bảng kê chi tiết phân tuyến và danh sách điểm giao hàng dùng để in (Delivery Manifest).
 * Kèm thông tin tài xế, phương tiện, địa chỉ chi tiết, số điện thoại người nhận và tiền thu từng điểm.
 */
public record DeliveryTripManifestResponse(
        Long tripId,
        String tripCode,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        String vehiclePlate,
        String driverName,
        String driverPhone,
        String status,
        LocalDateTime departedAt,
        LocalDateTime expectedReturnAt,
        int totalStops,
        BigDecimal totalTripAmount,
        String note,
        List<DeliveryTripStopResponse> stops,
        LocalDateTime printedAt,
        String printedByUsername) {
}
