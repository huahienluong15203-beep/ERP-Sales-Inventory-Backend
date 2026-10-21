package com.erp.backend.dto.trip;

import java.util.List;

/** S6-07: Chuyến giao kèm danh sách điểm giao. */
public record DeliveryTripDetailResponse(DeliveryTripSummaryResponse trip, List<DeliveryTripStopResponse> stops) {
}
