package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * Yêu cầu cập nhật đơn vị tính quy đổi hoặc thay đổi hệ số quy đổi (S2-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật đơn vị tính quy đổi (S2-07)")
public class UpdateProductUnitConversionRequest {

    @Size(max = 50, message = "Tên đơn vị quy đổi tối đa 50 ký tự")
    @Schema(description = "Tên đơn vị quy đổi (nếu có cập nhật tên)", example = "Thùng")
    private String unitName;

    @NotNull(message = "Hệ số quy đổi không được để trống")
    @DecimalMin(value = "0.0001", message = "Hệ số quy đổi phải lớn hơn 0")
    @Schema(description = "Hệ số quy đổi mới (S2-07: Đổi hệ số không ảnh hưởng giao dịch đã ghi trước đó)", example = "24")
    private BigDecimal conversionFactor;

    @Size(max = 50, message = "Mã vạch tối đa 50 ký tự")
    @Schema(description = "Mã vạch quy cách", example = "8934567890123")
    private String barcode;

    @Schema(description = "Đơn vị mặc định khi nhập kho", example = "true")
    private Boolean isDefaultPurchase;

    @Schema(description = "Đơn vị mặc định khi bán hàng", example = "false")
    private Boolean isDefaultSale;

    @Size(max = 255, message = "Mô tả tối đa 255 ký tự")
    @Schema(description = "Mô tả / quy cách chi tiết")
    private String description;

    @Pattern(regexp = "ACTIVE|INACTIVE", message = "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE")
    @Schema(description = "Trạng thái áp dụng: ACTIVE hoặc INACTIVE", example = "ACTIVE")
    private String status;

    @Schema(description = "Lý do thay đổi hệ số quy đổi (ghi nhật ký hệ thống S2-04)", example = "Nhà cung cấp thay đổi quy cách đóng gói thùng")
    private String changeReason;
}
