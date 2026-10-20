package com.erp.backend.dto.kpi;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * S8-04: Đặt chỉ tiêu doanh số tháng.
 * - period: "yyyy-MM" (vd "2026-10"), chỉ tháng hiện tại hoặc tương lai.
 * - targetType: REP (kèm salesRepId) hoặc REGION (kèm regionId).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesTargetCreateRequest {

    @NotBlank(message = "Vui lòng chọn tháng")
    private String period;

    @NotBlank(message = "Vui lòng chọn loại chỉ tiêu (REP / REGION)")
    private String targetType;

    private Long salesRepId;

    private Long regionId;

    private String region;

    @NotNull(message = "Vui lòng nhập chỉ tiêu doanh số")
    private BigDecimal targetAmount;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String note;
}
