package com.erp.backend.dto.customer;

import java.math.BigDecimal;

/**
 * S3-07 & S4-02: Kiểm tra khả năng tạo đơn hàng mới của đại lý.
 * Chặn tạo đơn nếu:
 * 1. Đại lý bị khóa giao dịch (transactionLocked = true)
 * 2. Đại lý ngừng hoạt động (status = INACTIVE)
 */
public record OrderCreationCheckResponse(
        Long customerId,
        String customerCode,
        String customerName,
        boolean allowed,
        String blockReason,
        BigDecimal creditLimit,
        Integer maxDebtDays,
        boolean transactionLocked) {
}
