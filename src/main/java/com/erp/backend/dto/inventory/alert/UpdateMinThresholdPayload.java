package com.erp.backend.dto.inventory.alert;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S5-09: Payload khai báo / cập nhật ngưỡng tồn tối thiểu cho SKU theo kho (AC1).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateMinThresholdPayload {

    @NotBlank(message = "Mã SKU không được để trống")
    private String productSku;

    @NotBlank(message = "Mã kho không được để trống")
    private String warehouseCode;

    @NotNull(message = "Định mức tồn tối thiểu không được để trống")
    @DecimalMin(value = "0.0", message = "Định mức tồn tối thiểu không được nhỏ hơn 0")
    private BigDecimal minThreshold;
}
