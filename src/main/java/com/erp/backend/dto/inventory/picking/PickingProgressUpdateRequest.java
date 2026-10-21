package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật tiến độ đồng loạt các dòng soạn hàng (S6-03)")
public class PickingProgressUpdateRequest {

    @NotEmpty(message = "Danh sách dòng cập nhật không được để trống")
    @Valid
    private List<ItemProgress> items;

    @Schema(description = "Tự động chốt hoàn tất phiếu nếu tất cả các dòng đã soạn xong")
    private Boolean completeIfAllPicked;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ItemProgress {
        @NotNull(message = "ID dòng hàng không được để trống")
        private Long itemId;

        @NotNull(message = "Số lượng thực soạn không được để trống")
        @jakarta.validation.constraints.DecimalMin(value = "0", message = "Số lượng thực soạn không được âm")
        private BigDecimal pickedQuantity;

        @com.fasterxml.jackson.annotation.JsonProperty("isPicked")
        @com.fasterxml.jackson.annotation.JsonAlias({"isCompleted", "picked"})
        private Boolean isPicked;

        private String shortageReason;

        private String actualBatchNumber;

        private String lotChangeReason;

        private String notes;
    }
}
