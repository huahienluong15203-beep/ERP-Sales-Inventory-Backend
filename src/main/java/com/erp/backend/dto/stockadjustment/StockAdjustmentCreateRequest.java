package com.erp.backend.dto.stockadjustment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * S8-03: DTO yêu cầu lập phiếu điều chỉnh tồn kho.
 * Bắt buộc chọn kho, lý do điều chỉnh và danh sách mặt hàng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentCreateRequest {

    @NotNull(message = "Kho hàng không được để trống")
    private Long warehouseId;

    /** Bắt buộc chọn lý do: DAMAGED_GOODS, LOST_GOODS, EXPIRED_DISPOSAL, OTHER */
    @NotBlank(message = "Bắt buộc chọn lý do điều chỉnh tồn kho")
    private String adjustmentReason;

    private String reasonDescription;

    private LocalDate adjustmentDate;

    private String notes;

    @NotEmpty(message = "Danh sách mặt hàng điều chỉnh không được để trống")
    @Valid
    private List<StockAdjustmentItemRequest> items;
}
