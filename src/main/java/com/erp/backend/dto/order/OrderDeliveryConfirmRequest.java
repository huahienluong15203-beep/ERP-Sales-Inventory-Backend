package com.erp.backend.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S6-08: Xác nhận giao hàng thành công (đơn Đã xuất -> Đã giao). Phải tải ảnh biên bản có chữ ký trước.
 * - receiverName: người nhận hàng (bắt buộc).
 * - deliveredAt: thời điểm giao thực tế (bỏ trống = bây giờ), không được ở tương lai / trước lúc xuất kho.
 * - lineDeliveries: số thực giao từng dòng (dòng không gửi = giao đủ); giao thiếu phải có lý do (ở dòng hoặc note),
 *   phần không giao được chở về và nhập lại kho.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDeliveryConfirmRequest {

    @NotBlank(message = "Bắt buộc nhập tên người nhận hàng")
    @Size(max = 100, message = "Tên người nhận tối đa 100 ký tự")
    private String receiverName;

    private LocalDateTime deliveredAt;

    @Valid
    private List<OrderLineDeliveryRequest> lineDeliveries;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
