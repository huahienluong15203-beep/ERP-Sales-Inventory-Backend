package com.erp.backend.dto.trip;

import java.math.BigDecimal;

/**
 * S6-07: Tổng quan hàng đi đường.
 * - activeTrips / overdueTrips: số chuyến đang chạy / đang trễ.
 * - ordersOnRoad, valueOnRoad: số đơn và tiền hàng đang nằm trên các chuyến.
 * - unassignedOrders, unassignedValue: đơn đã xuất kho nhưng chưa xếp chuyến (vẫn là hàng đã rời kho).
 */
public record InTransitSummaryResponse(
        int activeTrips,
        int overdueTrips,
        int ordersOnRoad,
        BigDecimal valueOnRoad,
        int unassignedOrders,
        BigDecimal unassignedValue) {
}
