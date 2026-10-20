package com.erp.backend.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/**
 * Yêu cầu lập bút toán điều chỉnh cho phiếu thu đã ghi nhận.
 * Quy tắc nghiệp vụ kế toán: Đã ghi nhận thì không xoá, chỉ lập bút toán điều chỉnh.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAdjustmentRequest {

    @NotBlank(message = "Vui lòng nhập lý do lập bút toán điều chỉnh")
    @Size(max = 500, message = "Lý do điều chỉnh không được vượt quá 500 ký tự")
    private String reason;
}
