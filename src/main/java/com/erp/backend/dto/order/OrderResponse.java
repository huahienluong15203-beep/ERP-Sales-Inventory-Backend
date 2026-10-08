package com.erp.backend.dto.order;

import com.erp.backend.dto.customer.CreditStatusResponse;

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
        List<String> warnings,
        // S4-02: công nợ của đại lý khi thêm đơn này (chỉ có khi đơn còn nháp); credit.exceedsLimit = đơn cần duyệt
        CreditStatusResponse credit,
        // S4-05: lý do cần duyệt + mức vi phạm (lúc chốt đơn), ý kiến gần nhất của người duyệt
        List<ApprovalReason> approvalReasons,
        String lastApprovalComment,
        LocalDateTime submittedAt,
        LocalDateTime approvedAt,
        String approvedByUsername) {

    public record DeliveryAddressInfo(Long id, String label, String address, String receiverName, String receiverPhone) {
    }
}
