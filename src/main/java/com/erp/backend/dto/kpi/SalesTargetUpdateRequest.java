package com.erp.backend.dto.kpi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** S8-04: Sửa chỉ tiêu (chỉ kỳ chưa qua). Bắt buộc lý do, ghi nhật ký trước / sau. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetUpdateRequest {

    @NotNull(message = "Vui lòng nhập chỉ tiêu doanh số")
    private BigDecimal targetAmount;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;

    @NotBlank(message = "Bắt buộc nhập lý do sửa chỉ tiêu")
    @Size(max = 500, message = "Lý do tối đa 500 ký tự")
    private String reason;
}
