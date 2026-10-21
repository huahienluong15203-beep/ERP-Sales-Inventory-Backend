package com.erp.backend.dto.inventory.picking;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu tạo phiếu soạn hàng từ các đơn đã duyệt (S6-03)")
public class PickingListCreateRequest {

    @NotNull(message = "Kho xuất hàng không được để trống")
    @Schema(description = "ID của kho xuất hàng")
    private Long warehouseId;

    @NotEmpty(message = "Danh sách đơn hàng cần gom không được để trống")
    @Schema(description = "Danh sách ID đơn hàng đã duyệt cần gom vào đợt soạn")
    private List<Long> orderIds;

    @Schema(description = "ID nhân viên được phân công soạn hàng (tùy chọn)")
    private Long pickerId;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    @Schema(description = "Ghi chú cho đợt soạn hàng")
    private String note;
}
