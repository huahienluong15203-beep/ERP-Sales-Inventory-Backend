package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S6-09: Dữ liệu theo dõi trạng thái giao hàng và mốc thời gian cho đại lý (Customer Portal).
 * - AC1: Mốc thời gian: đã duyệt, đang soạn, đã xuất, đang giao, đã giao.
 * - AC2: Xem được số lượng thực giao từng dòng hàng.
 */
public record OrderDeliveryTimelineResponse(
        Long orderId,
        String orderCode,
        String status,
        String statusLabel,
        int currentStep,
        Long customerId,
        String customerCode,
        String customerName,
        String deliveryAddress,
        String receiverName,
        String receiverPhone,
        String warehouseCode,
        String warehouseName,
        boolean hasShortage,
        int shortageLineCount,
        BigDecimal totalAmount,
        TimelineSteps steps,
        DeliveryTripInfo tripInfo,
        List<OrderDeliveryLineItem> lines
) {
    public record TimelineSteps(
            StepDetail approved,
            StepDetail picking,
            StepDetail dispatched,
            StepDetail shipping,
            StepDetail delivered
    ) {}

    public record StepDetail(
            String statusKey,
            String label,
            String subLabel,
            boolean completed,
            boolean current,
            LocalDateTime time,
            String actor,
            String note
    ) {}

    public record DeliveryTripInfo(
            Long tripId,
            String tripCode,
            String vehiclePlate,
            String driverName,
            String driverPhone,
            Integer stopSequence,
            String status,
            LocalDateTime departedAt,
            LocalDateTime expectedReturnAt
    ) {}

    public record OrderDeliveryLineItem(
            Long lineId,
            Integer lineNo,
            Long productId,
            String productSku,
            String productName,
            String unitName,
            BigDecimal conversionFactor,
            BigDecimal orderedQuantity,
            BigDecimal dispatchedQuantity,
            BigDecimal deliveredQuantity,
            BigDecimal shortageQuantity,
            boolean isShortage,
            String shortageReason
    ) {}
}
