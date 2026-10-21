package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin dòng mặt hàng đã gộp kèm vị trí lưu và thứ tự đường đi (S6-03)")
public class PickingListItemResponse {

    private Long id;
    private Long productId;
    private String productSku;
    private String productName;
    private String baseUnit;

    // Vị trí lưu kho & lộ trình lấy hàng
    private Long locationId;
    private String locationCode;
    private String locationName;
    private String zone;
    private String shelf;
    private Integer routeSequence;

    // Lô hàng gợi ý theo FEFO
    private String suggestedBatchNumber;
    private LocalDate suggestedExpiredDate;
    private String actualBatchNumber;
    private String lotChangeReason;

    // Tiến độ soạn hàng
    private BigDecimal requestedQuantity;
    private BigDecimal pickedQuantity;
    @com.fasterxml.jackson.annotation.JsonProperty("isPicked")
    @com.fasterxml.jackson.annotation.JsonAlias({"isCompleted", "picked"})
    private Boolean isPicked;
    private String shortageReason;
    private String notes;

    // Phân bổ về từng đơn
    private List<PickingItemAllocationResponse> allocations;
}
