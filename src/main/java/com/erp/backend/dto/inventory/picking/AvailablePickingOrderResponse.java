package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin đơn hàng đã duyệt sẵn sàng gom vào đợt soạn (S6-03)")
public class AvailablePickingOrderResponse {

    private Long orderId;
    private String orderCode;
    private Long customerId;
    private String customerCode;
    private String customerName;
    private LocalDate desiredDeliveryDate;
    private Integer totalLines;
    private BigDecimal totalAmount;
    private LocalDateTime approvedAt;
    private String status;
    private Long warehouseId;
    private String warehouseName;
}
