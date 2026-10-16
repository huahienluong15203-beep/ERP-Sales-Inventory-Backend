package com.erp.backend.dto.stockadjustment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * S8-03: DTO dòng hàng điều chỉnh trong yêu cầu lập phiếu.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAdjustmentItemRequest {

    @NotNull(message = "Sản phẩm không được để trống")
    private Long productId;

    @NotNull(message = "Số lượng điều chỉnh không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng điều chỉnh phải lớn hơn 0")
    private BigDecimal adjustmentQuantity;

    /** Bắt buộc nhập nếu sản phẩm có quản lý theo lô */
    private String batchNumber;

    private String lineNotes;
}
