package com.erp.backend.dto.stocktake;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S5-08: Tạo phiếu kiểm kê theo kho, có thể giới hạn theo nhóm hàng (gồm nhóm con). */
@Getter
@Setter
public class StockTakeCreateRequest {

    @NotNull(message = "Vui lòng chọn kho kiểm kê")
    private Long warehouseId;

    private Long categoryId;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
