package com.erp.backend.dto.inventory.transfer;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReceiveTransferLineItem {

    @NotNull(message = "Mã dòng hàng (lineId) không được để trống")
    private String lineId;

    @NotNull(message = "Số lượng thực nhận không được để trống")
    private BigDecimal receivedQuantity;

    private String discrepancyReason;
}
