package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * Yêu cầu khai báo đơn vị tính quy đổi mới cho SKU (S2-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu khai báo đơn vị tính quy đổi (S2-07)")
public class CreateProductUnitConversionRequest {

    @NotBlank(message = "Tên đơn vị quy đổi không được để trống")
    @Size(max = 50, message = "Tên đơn vị quy đổi tối đa 50 ký tự")
    @Schema(description = "Tên đơn vị quy đổi (vd: Lốc, Thùng, Két, Khay...)", example = "Thùng")
    private String unitName;

    @NotNull(message = "Hệ số quy đổi không được để trống")
    @DecimalMin(value = "0.0001", message = "Hệ số quy đổi phải lớn hơn 0")
    @Schema(description = "Hệ số quy đổi so với đơn vị cơ sở (vd: 1 Thùng = 24 Lon -> factor = 24)", example = "24")
    private BigDecimal conversionFactor;

    @Size(max = 50, message = "Mã vạch tối đa 50 ký tự")
    @Schema(description = "Mã vạch của quy cách này (Barcode thùng / lốc nếu có)", example = "8934567890123")
    private String barcode;

    @Schema(description = "Đơn vị mặc định khi nhập kho", example = "true")
    @Builder.Default
    private Boolean isDefaultPurchase = false;

    @Schema(description = "Đơn vị mặc định khi bán lẻ / bán buôn", example = "false")
    @Builder.Default
    private Boolean isDefaultSale = false;

    @Size(max = 255, message = "Mô tả tối đa 255 ký tự")
    @Schema(description = "Mô tả / quy cách chi tiết", example = "Thùng carton 24 lon 330ml")
    private String description;
}
