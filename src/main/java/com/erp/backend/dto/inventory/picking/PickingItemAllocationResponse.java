package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Chi tiết phân bổ mặt hàng gom về từng đơn hàng (S6-03)")
public class PickingItemAllocationResponse {

    private Long id;
    private Long orderId;
    private String orderCode;
    private String customerName;
    private BigDecimal requestedQuantity;
    private BigDecimal pickedQuantity;
    private String shortageReason;
}
