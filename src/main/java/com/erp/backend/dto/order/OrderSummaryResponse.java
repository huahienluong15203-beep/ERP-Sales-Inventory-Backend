package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * S3-09 & S5-01: Một dòng trong danh sách đơn (không kèm dòng hàng).
 * S4-07: thêm nhân viên phụ trách, khu vực, ngày tạo.
 * S5-01: thêm hasShortage (đánh dấu đơn hàng có dòng giao thiếu).
 */
public record OrderSummaryResponse(
        Long id,
        String code,
        String status,
        Long customerId,
        String customerCode,
        String customerName,
        LocalDate desiredDeliveryDate,
        int lineCount,
        BigDecimal totalAmount,
        String createdByUsername,
        LocalDateTime updatedAt,
        Long salesRepId,
        String salesRepName,
        Long regionId,
        String regionName,
        LocalDateTime createdAt,
        Boolean hasShortage) {

    public OrderSummaryResponse(
            Long id,
            String code,
            String status,
            Long customerId,
            String customerCode,
            String customerName,
            LocalDate desiredDeliveryDate,
            int lineCount,
            BigDecimal totalAmount,
            String createdByUsername,
            LocalDateTime updatedAt,
            Long salesRepId,
            String salesRepName,
            Long regionId,
            String regionName,
            LocalDateTime createdAt) {
        this(id, code, status, customerId, customerCode, customerName, desiredDeliveryDate,
                lineCount, totalAmount, createdByUsername, updatedAt, salesRepId, salesRepName,
                regionId, regionName, createdAt, false);
    }
}
