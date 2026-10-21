package com.erp.backend.dto.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S5-01: Thông tin số lượng thực giao cho từng dòng hàng khi giao hàng.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderLineDeliveryRequest {

    @NotNull(message = "Mã dòng hàng không được để trống")
    private Long lineId;

    @NotNull(message = "Số lượng thực giao không được để trống")
    @DecimalMin(value = "0", message = "Số lượng thực giao không được âm")
    private BigDecimal deliveredQuantity;

    private String shortageReason;
}
