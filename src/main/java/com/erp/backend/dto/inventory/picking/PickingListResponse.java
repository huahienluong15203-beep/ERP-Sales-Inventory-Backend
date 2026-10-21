package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin chi tiết phiếu soạn hàng (S6-03)")
public class PickingListResponse {

    private Long id;
    private String code;
    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private String status; // DRAFT, ASSIGNED, IN_PROGRESS, COMPLETED, CANCELLED
    private Long pickerId;
    private String pickerName;
    private String note;
    private String cancelReason;

    private Integer totalOrders;
    private Integer totalItems;
    private BigDecimal totalRequestedQuantity;
    private BigDecimal totalPickedQuantity;
    private BigDecimal completionPercentage;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime cancelledAt;

    private Long createdById;
    private String createdByUsername;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<PickingListOrderResponse> orders;
    private List<PickingListItemResponse> items;
}
