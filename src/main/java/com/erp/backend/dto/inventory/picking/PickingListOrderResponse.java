package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin đơn hàng được gom trong đợt soạn (S6-03)")
public class PickingListOrderResponse {

    private Long id;
    private Long orderId;
    private String orderCode;
    private String customerName;
    private Integer totalLines;
    private BigDecimal totalAmount;
    private String orderStatus;
    private LocalDateTime createdAt;
}
