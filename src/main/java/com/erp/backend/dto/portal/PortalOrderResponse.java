package com.erp.backend.dto.portal;

import com.erp.backend.dto.customer.CreditStatusResponse;
import com.erp.backend.dto.order.OrderFulfillmentInfo;
import com.erp.backend.dto.order.OrderResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * S4-10 / S6-09: Đơn hàng đại lý xem trên cổng (xem trước giỏ hàng hoặc đơn đã đặt).
 * Không có giá sàn, giá vốn, chi tiết vi phạm nội bộ.
 * S6-09: Bổ sung thông tin fulfillment, giao thiếu và số lượng thực giao từng dòng hàng.
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
        CreditStatusResponse credit,
        Boolean hasShortage,
        OrderFulfillmentInfo fulfillment) {

    public PortalOrderResponse(
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
        this(id, code, status, statusLabel, deliveryAddress, desiredDeliveryDate, note, lines, subtotal,
                discountTotal, totalAmount, createdAt, submittedAt, approvedAt, lastApprovalComment, cancelReason,
                warnings, credit, false, null);
    }

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
            BigDecimal maxAllowedQuantity,
            BigDecimal deliveredQuantity,
            BigDecimal shortageQuantity,
            Boolean isShortage,
            String shortageReason) {

        public Line(
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
            this(lineNo, productId, productSku, productName, unitName, conversionFactor, quantity,
                    pricePerUnit, grossAmount, discountAmount, netAmount, availableStock, isOverStock,
                    maxAllowedQuantity, null, BigDecimal.ZERO, false, null);
        }
    }
}
