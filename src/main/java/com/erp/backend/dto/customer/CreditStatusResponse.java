package com.erp.backend.dto.customer;

import java.math.BigDecimal;

/**
 * S4-02: Tình trạng công nợ của đại lý khi tạo đơn.
 *
 * @param creditLimit       hạn mức công nợ
 * @param currentDebt       công nợ hiện tại (tổng đơn đã duyệt, chưa thu)
 * @param availableCredit   còn lại = hạn mức - công nợ hiện tại (có thể âm nếu đã vượt)
 * @param orderAmount       tiền đơn đang xét (0 nếu chỉ xem công nợ)
 * @param debtAfterOrder    công nợ hiện tại + tiền đơn
 * @param exceedsLimit      công nợ sau đơn vượt hạn mức -> đơn phải qua duyệt
 * @param exceededAmount    số tiền vượt hạn mức (mức vi phạm), 0 nếu không vượt
 * @param overdue           có khoản nợ quá số ngày cho phép -> chặn tạo đơn mới
 * @param overdueDays       số ngày quá hạn của khoản nợ lâu nhất
 * @param blocked           true = không được tạo đơn mới
 * @param message           thông báo hiển thị cho người dùng (null nếu bình thường)
 */
public record CreditStatusResponse(
        Long customerId,
        String customerCode,
        String customerName,
        BigDecimal creditLimit,
        BigDecimal currentDebt,
        BigDecimal availableCredit,
        BigDecimal orderAmount,
        BigDecimal debtAfterOrder,
        boolean exceedsLimit,
        BigDecimal exceededAmount,
        Integer maxDebtDays,
        boolean overdue,
        long overdueOrderCount,
        BigDecimal overdueAmount,
        long overdueDays,
        boolean blocked,
        String message) {
}
