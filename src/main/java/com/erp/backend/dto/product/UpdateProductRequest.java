package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.math.BigDecimal;

/**
 * Yêu cầu cập nhật thông tin sản phẩm (S2-05).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật sản phẩm (S2-05)")
public class UpdateProductRequest {

    @NotBlank(message = "Tên sản phẩm không được để trống")
    @Size(max = 200, message = "Tên sản phẩm tối đa 200 ký tự")
    @Schema(description = "Tên sản phẩm", example = "Nước ngọt Coca-Cola lon 330ml")
    private String name;

    @Size(max = 100, message = "Nhóm hàng tối đa 100 ký tự")
    @Schema(description = "Nhóm hàng / Ngành hàng", example = "Nước giải khát có gas")
    private String category;

    @Schema(description = "S2-06: id nhóm hàng trong cây nhóm hàng. Có giá trị thì ô category tự lấy theo tên nhóm", example = "3")
    private Long categoryId;

    @NotBlank(message = "Đơn vị tính cơ sở không được để trống")
    @Size(max = 30, message = "Đơn vị tính cơ sở tối đa 30 ký tự")
    @Schema(description = "Đơn vị tính cơ sở (vd: Lon, Chai, Hộp...)", example = "Lon")
    private String baseUnit;

    @Size(max = 100, message = "Quy cách đóng gói tối đa 100 ký tự")
    @Schema(description = "Quy cách đóng gói chung", example = "Thùng 24 lon")
    private String packaging;

    @DecimalMin(value = "0.0", inclusive = true, message = "Giá vốn không được âm")
    @Digits(integer = 13, fraction = 2, message = "Giá vốn tối đa 13 chữ số nguyên và 2 chữ số thập phân")
    @Schema(description = "Giá vốn / Giá nhập (VNĐ)", example = "215000")
    private BigDecimal costPrice;

    @Size(max = 50, message = "Mã vạch tối đa 50 ký tự")
    @Schema(description = "Mã vạch cơ sở (Barcode)", example = "8934567890123")
    private String barcode;

    @Size(max = 500, message = "Đường dẫn ảnh tối đa 500 ký tự")
    @Schema(description = "Đường dẫn ảnh sản phẩm")
    private String imageUrl;

    @Size(max = 1000, message = "Mô tả tối đa 1000 ký tự")
    @Schema(description = "Mô tả chi tiết sản phẩm")
    private String description;

    @Pattern(regexp = "ACTIVE|INACTIVE", message = "Trạng thái chỉ nhận ACTIVE hoặc INACTIVE")
    @Schema(description = "Trạng thái kinh doanh", example = "ACTIVE")
    private String status;
}
