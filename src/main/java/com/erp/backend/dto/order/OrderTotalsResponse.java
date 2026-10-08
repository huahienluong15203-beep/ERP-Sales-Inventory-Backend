package com.erp.backend.dto.order;

import java.math.BigDecimal;

/** S4-07: Tổng số đơn và tổng tiền (tổng phải thu) của toàn bộ kết quả đang lọc, không chỉ trang đang xem. */
public record OrderTotalsResponse(long orderCount, BigDecimal totalAmount) {
}
