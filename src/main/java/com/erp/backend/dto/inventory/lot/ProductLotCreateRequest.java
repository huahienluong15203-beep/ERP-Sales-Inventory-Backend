package com.erp.backend.dto.inventory.lot;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu khai báo lô hàng mới (S6-01)")
public class ProductLotCreateRequest {

    @NotNull(message = "Mã sản phẩm không được để trống")
    private Long productId;

    @NotNull(message = "Kho lưu trữ không được để trống")
    private Long warehouseId;

    @NotBlank(message = "Số lô sản xuất không được để trống")
    @Size(max = 100, message = "Số lô sản xuất tối đa 100 ký tự")
    private String batchNumber;

    @Schema(description = "Ngày sản xuất")
    private LocalDate manufactureDate;

    @NotNull(message = "Hạn sử dụng không được để trống")
    @Schema(description = "Hạn sử dụng")
    private LocalDate expiredDate;

    @DecimalMin(value = "0", message = "Số lượng tồn ban đầu không được âm")
    @Builder.Default
    private BigDecimal initialQuantity = BigDecimal.ZERO;

    private Long supplierId;

    @Size(max = 500, message = "Ghi chú tối đa 500 ký tự")
    private String notes;
}
