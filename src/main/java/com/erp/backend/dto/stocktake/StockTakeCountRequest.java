package com.erp.backend.dto.stocktake;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/** S5-08: Nhập số đếm thực tế cho các dòng kiểm kê (có thể nhập nhiều lần trước khi chốt). */
@Getter
@Setter
public class StockTakeCountRequest {

    @NotEmpty(message = "Chưa có dòng nào để cập nhật")
    @Size(max = 2000, message = "Tối đa 2000 dòng mỗi lần")
    @Valid
    private List<Line> lines;

    @Getter
    @Setter
    public static class Line {
        private Long lineId;
        private BigDecimal countedQuantity;
        @Size(max = 255, message = "Ghi chú dòng tối đa 255 ký tự")
        private String note;
    }
}
