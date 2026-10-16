package com.erp.backend.dto.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/** S7-02: Chi tiết phân bổ cho từng hoá đơn khi chọn đối trừ chỉ định. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentAllocationItemRequest {

    @NotNull(message = "Mã hoá đơn không được để trống")
    private Long invoiceId;

    @NotNull(message = "Số tiền cấn trừ không được để trống")
    @DecimalMin(value = "0.01", message = "Số tiền cấn trừ phải lớn hơn 0")
    private BigDecimal amount;
}
