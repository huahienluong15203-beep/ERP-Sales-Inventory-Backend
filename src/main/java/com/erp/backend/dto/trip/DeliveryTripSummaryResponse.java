package com.erp.backend.dto.trip;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * S6-07: Một chuyến giao kèm tiến độ.
 * - totalStops = số điểm giao (đơn); delivered / failed / remaining = đã giao / giao không thành công / còn đang chở.
 * - valueOnRoad: tiền hàng còn nằm trên xe (đơn chưa giao + phần chưa giao được của đơn đã xử lý, chưa nhập lại kho).
 * - overdue: chuyến đang chạy đã quá thời điểm dự kiến; overdueMinutes = số phút trễ.
 */
public record DeliveryTripSummaryResponse(
        Long id,
        String code,
        Long warehouseId,
        String warehouseCode,
        String warehouseName,
        String vehiclePlate,
        String driverName,
        String driverPhone,
        String status,
        LocalDateTime departedAt,
        LocalDateTime expectedReturnAt,
        LocalDateTime completedAt,
        int totalStops,
        int deliveredStops,
        int failedStops,
        int remainingStops,
        BigDecimal totalValue,
        BigDecimal valueOnRoad,
        boolean overdue,
        long overdueMinutes,
        String note) {
}
