package com.erp.backend.dto.portal;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.order.OrderResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S4-10: Đơn hàng đại lý xem trên cổng (xem trước giỏ hàng hoặc đơn đã đặt).
 * Không có giá sàn, giá vốn, chi tiết vi phạm nội bộ.
 */
public record PortalOrderResponse(
        Long id,
        String code,
        String status,
        String statusLabel,
        OrderResponse.DeliveryAddressInfo deliveryAddress,
        LocalDate desiredDeliveryDate,
        String note,
        List<Line> lines,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal totalAmount,
        LocalDateTime createdAt,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        String lastApprovalComment,
        String cancelReason,
        List<String> warnings,
        CreditStatusResponse credit) {

    /** @param pricePerUnit giá 1 ĐVT đã chọn */
    public record Line(
            Integer lineNo,
            Long productId,
            String productSku,
            String productName,
            String unitName,
            BigDecimal conversionFactor,
            BigDecimal quantity,
            BigDecimal pricePerUnit,
            BigDecimal grossAmount,
            BigDecimal discountAmount,
            BigDecimal netAmount,
            BigDecimal availableStock,
            Boolean isOverStock,
            BigDecimal maxAllowedQuantity) {
    }
}
