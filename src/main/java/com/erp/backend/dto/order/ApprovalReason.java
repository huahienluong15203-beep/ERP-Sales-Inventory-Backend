package com.erp.backend.dto.order;

import java.math.BigDecimal;

/**
 * S4-05: Lý do đơn cần duyệt và mức vi phạm.
 *
 * @param code             CREDIT_LIMIT (vượt hạn mức công nợ) | BELOW_FLOOR_PRICE (bán dưới giá sàn) | PORTAL_ORDER (đại lý tự đặt)
 * @param label            tên lý do hiển thị
 * @param detail           mô tả mức vi phạm (số tiền, %)
 * @param violationAmount  số tiền vi phạm (VND)
 * @param violationPercent mức vi phạm theo % (null nếu không tính được, vd hạn mức = 0)
 */
public record ApprovalReason(
        String code,
        String label,
        String detail,
        BigDecimal violationAmount,
        BigDecimal violationPercent) {

    public static final String CREDIT_LIMIT = "CREDIT_LIMIT";
    public static final String BELOW_FLOOR_PRICE = "BELOW_FLOOR_PRICE";
    /** S4-10: đơn đại lý tự đặt qua cổng, chờ nhân viên phụ trách xác nhận */
    public static final String PORTAL_ORDER = "PORTAL_ORDER";
}
