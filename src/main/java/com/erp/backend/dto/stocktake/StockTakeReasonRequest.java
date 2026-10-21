package com.erp.backend.dto.stocktake;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** S5-08: Lý do chốt / huỷ phiếu kiểm kê (bắt buộc, kiểm ở service). */
@Getter
@Setter
public class StockTakeReasonRequest {

    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
