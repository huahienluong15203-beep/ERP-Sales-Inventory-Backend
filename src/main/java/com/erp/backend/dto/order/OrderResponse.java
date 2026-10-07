package com.erp.backend.dto.order;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** S3-09: Đơn hàng (đơn nháp). id = null khi chỉ xem trước, chưa lưu. */
public record OrderResponse(
        Long id,
        String code,
        String status,
        Long customerId,
        String customerCode,
        String customerName,
        String customerGroup,
        String customerGroupLabel,
        DeliveryAddressInfo deliveryAddress,
        LocalDate desiredDeliveryDate,
        String note,
        List<OrderLineResponse> lines,
        BigDecimal subtotal,
        BigDecimal discountTotal,
        BigDecimal totalAmount,
        String createdByUsername,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        // S3-07 AC3: cảnh báo hiển thị cho người dùng (vd đại lý đang bị khoá giao dịch), rỗng nếu không có
        List<String> warnings) {

    public record DeliveryAddressInfo(Long id, String label, String address, String receiverName, String receiverPhone) {
    }
}
