package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật tiến độ soạn cho một dòng hàng trên di động (S6-03)")
public class PickingItemUpdateRequest {

    @NotNull(message = "Số lượng thực soạn không được để trống")
    @DecimalMin(value = "0", message = "Số lượng thực soạn không được âm")
    @Schema(description = "Số lượng thực tế thủ kho đã lấy được")
    private BigDecimal pickedQuantity;

    @com.fasterxml.jackson.annotation.JsonProperty("isPicked")
    @com.fasterxml.jackson.annotation.JsonAlias({"isCompleted", "picked"})
    @Schema(description = "Đánh dấu đã hoàn thành lấy dòng hàng này (true/false)")
    private Boolean isPicked;

    @Size(max = 500, message = "Lý do soạn thiếu tối đa 500 ký tự")
    @Schema(description = "Lý do soạn thiếu (BẮT BUỘC nếu số lượng thực soạn < số lượng yêu cầu)")
    private String shortageReason;

    @Size(max = 100, message = "Số lô tối đa 100 ký tự")
    @Schema(description = "Số lô thực tế đã lấy (nếu khác lô gợi ý)")
    private String actualBatchNumber;

    @Size(max = 500, message = "Lý do đổi lô tối đa 500 ký tự")
    @Schema(description = "Lý do đổi lô khác với lô gợi ý FEFO (BẮT BUỘC nếu actualBatchNumber != suggestedBatchNumber)")
    private String lotChangeReason;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String notes;
}
