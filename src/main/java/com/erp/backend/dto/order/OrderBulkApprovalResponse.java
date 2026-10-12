package com.erp.backend.dto.order;

import java.util.List;

/** Kết quả xử lý hàng loạt: đơn nào thành công, đơn nào lỗi và vì sao (đơn lỗi không làm hỏng các đơn khác). */
public record OrderBulkApprovalResponse(int total, int succeeded, int failed, List<Item> results) {

    public record Item(Long orderId, String orderCode, boolean success, String status, String message) {
    }
}
