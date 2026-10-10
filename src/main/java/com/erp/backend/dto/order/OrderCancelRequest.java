package com.erp.backend.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * S4-06 / SCRUM-158 AC2: Yêu cầu huỷ đơn hàng.
 * Bắt buộc nhập lý do huỷ theo quy chuẩn S4-06 AC2.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCancelRequest {

    @NotBlank(message = "Bắt buộc nhập lý do hủy đơn hàng theo quy chuẩn S4-06 AC2!")
    @Size(max = 500, message = "Lý do hủy tối đa 500 ký tự")
    private String reason;
}
