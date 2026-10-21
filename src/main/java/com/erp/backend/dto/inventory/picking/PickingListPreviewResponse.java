package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả xem trước gom đơn & gộp mặt hàng (S6-03)")
public class PickingListPreviewResponse {

    private Long warehouseId;
    private String warehouseCode;
    private String warehouseName;
    private int totalOrders;
    private int totalItems;
    private BigDecimal totalQuantity;
    private List<String> orderCodes;
    private List<PickingListItemResponse> items;
}
