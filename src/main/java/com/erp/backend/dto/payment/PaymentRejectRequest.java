package com.erp.backend.dto.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

/** S7-05: Yêu cầu từ chối xác nhận nộp quỹ từ Kế toán. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentRejectRequest {

    @NotBlank(message = "Vui lòng nhập lý do từ chối")
    @Size(max = 500, message = "Lý do từ chối không được vượt quá 500 ký tự")
    private String reason;
}
