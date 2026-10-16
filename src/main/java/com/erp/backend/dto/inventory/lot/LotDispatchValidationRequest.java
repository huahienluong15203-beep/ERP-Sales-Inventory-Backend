package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu kiểm tra HSD & tính hợp lệ khi xuất lô hàng (S6-01)")
public class LotDispatchValidationRequest {

    @NotNull(message = "Mã sản phẩm không được để trống")
    private Long productId;

    @NotNull(message = "Mã kho không được để trống")
    private Long warehouseId;

    @NotBlank(message = "Số lô không được để trống")
    private String batchNumber;

    @NotNull(message = "Số lượng xuất không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng xuất phải lớn hơn 0")
    private BigDecimal quantity;

    @Schema(description = "Ngưỡng cảnh báo cận hạn (ngày, mặc định 30)")
    private Integer nearExpiryDays;

    @Schema(description = "Xác nhận ghi đè xuất kho dù lô đã quá hạn (chỉ Quản lý kho / Admin)")
    private Boolean overrideExpired;

    @Size(max = 500, message = "Lý do ghi đè tối đa 500 ký tự")
    @Schema(description = "Lý do ghi đè cho phép xuất lô quá hạn (bắt buộc khi overrideExpired = true)")
    private String overrideReason;
}
