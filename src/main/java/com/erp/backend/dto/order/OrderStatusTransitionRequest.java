package com.erp.backend.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S4-06 / SCRUM-158: Yêu cầu chuyển trạng thái đơn hàng theo vòng đời:
 * Nháp -> Chờ duyệt -> Đã duyệt -> Đang soạn hàng (PICKING) -> Đã xuất (DISPATCHED) -> Đã giao (DELIVERED) -> Đóng (CLOSED).
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
}
