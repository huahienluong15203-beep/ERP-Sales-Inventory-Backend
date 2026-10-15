package com.erp.backend.dto.order;

/** S6-05: Kết quả xuất kho: đơn đã xuất và đơn nợ hàng mới tách ra (null nếu xuất đủ / không tạo đơn nợ). */
public record OrderDispatchResponse(OrderResponse order, OrderResponse backorder) {
}
