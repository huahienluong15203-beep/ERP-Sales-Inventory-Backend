package com.erp.backend.dto.order;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** S6-08: Ghi nhận giao không thành công. Bắt buộc lý do; toàn bộ hàng của đơn được nhập lại kho. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderDeliveryFailedRequest {

    @NotBlank(message = "Bắt buộc nhập lý do giao không thành công")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
