package com.erp.backend.dto.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * S4-06 / SCRUM-158 & S5-01: Yêu cầu chuyển trạng thái đơn hàng theo vòng đời:
 * Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng (PICKING) -> Đã xuất (DISPATCHED) -> Đã giao (DELIVERED) -> Đóng (CLOSED).
 * S5-01: kèm lineDeliveries khi giao hàng (DELIVERED) để ghi nhận số lượng thực giao và phát hiện giao thiếu.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderStatusTransitionRequest {

    @NotBlank(message = "Trạng thái mới không được để trống")
    private String status;

    @Size(max = 500, message = "Ghi chú/lý do tối đa 500 ký tự")
    private String note;

    @Valid
    private List<OrderLineDeliveryRequest> lineDeliveries;

    public OrderStatusTransitionRequest(String status, String note) {
        this(status, note, null);
    }
}
