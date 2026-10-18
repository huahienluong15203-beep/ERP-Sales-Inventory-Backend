package com.erp.backend.dto.order;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.List;

/**
 * S3-09: Lưu nháp / xem trước đơn hàng.
 * deliveryAddressId để trống thì dùng điểm giao mặc định của đại lý.
 */
@Getter
@Setter
public class OrderDraftRequest {
    /** Chỉ dùng khi xem trước lúc đang sửa một đơn nháp có sẵn (S3-07: đại lý bị khoá vẫn xử lý tiếp đơn dở). */
    private Long draftId;
    private Long customerId;
    private Long deliveryAddressId;
    private LocalDate desiredDeliveryDate;
    private String note;
    /** Hình thức thanh toán: CREDIT (Công nợ) hoặc COD (Thanh toán khi nhận hàng). */
    private String paymentMethod;
    private List<OrderLineRequest> lines;
}
