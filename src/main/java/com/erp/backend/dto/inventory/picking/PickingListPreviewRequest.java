package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu xem trước kết quả gom đơn và gộp mặt hàng (S6-03)")
public class PickingListPreviewRequest {

    @NotNull(message = "Kho xuất hàng không được để trống")
    @Schema(description = "ID của kho xuất hàng")
    private Long warehouseId;

    @NotEmpty(message = "Danh sách đơn hàng không được để trống")
    @Schema(description = "Danh sách ID các đơn hàng cần gom thử")
    private List<Long> orderIds;
}
