package com.erp.backend.dto.order;

import java.time.LocalDate;
import java.util.List;

/**
 * S4-07: Bộ lọc danh sách đơn hàng.
 *
 * @param statuses   một hoặc nhiều trạng thái (rỗng = tất cả)
 * @param customerId đại lý
 * @param salesRepId nhân viên kinh doanh phụ trách đại lý
 * @param regionId   khu vực của đại lý
 * @param fromDate   ngày tạo đơn từ (tính cả ngày này)
 * @param toDate     ngày tạo đơn đến (tính cả ngày này)
 * @param keyword    mã đơn, mã / tên đại lý
 */
public record OrderSearchCriteria(
        List<String> statuses,
        Long customerId,
        Long salesRepId,
        Long regionId,
        LocalDate fromDate,
        LocalDate toDate,
        String keyword) {
}
