package com.erp.backend.dto.inventory.transfer;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateStockTransferLinePayload {

    @NotNull(message = "Mã sản phẩm không được để trống")
    private Long productId;

    private String productSku;

    private String productName;

    private String category;

    private String unit;

    private BigDecimal sourceAvailableStock;

    @NotNull(message = "Số lượng điều chuyển không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng điều chuyển phải lớn hơn 0")
    private BigDecimal transferQuantity;

    private String batchNumber;

    private String expiredDate;
}
