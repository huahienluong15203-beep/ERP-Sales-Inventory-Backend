package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.math.BigDecimal;

/**
 * Yêu cầu tính toán quy đổi đơn vị tính nhanh cho nhân viên kho / bán hàng (S2-07).
 * Cho phép truyền productId hoặc sku kèm tên đơn vị và số lượng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu tính toán quy đổi đơn vị tính (S2-07)")
public class UnitConversionCalculateRequest {

    @Schema(description = "ID sản phẩm (tuỳ chọn nếu có sku)", example = "1")
    private Long productId;

    @Schema(description = "Mã SKU sản phẩm (tuỳ chọn nếu có productId)", example = "SP-COCA-330")
    private String sku;

    @NotBlank(message = "Đơn vị tính cần quy đổi không được để trống")
    @Schema(description = "Đơn vị tính được chọn khi nhập/xuất (vd: Thùng, Lốc, Lon)", example = "Thùng")
    private String unitName;

    @NotNull(message = "Số lượng không được để trống")
    @DecimalMin(value = "0.0001", message = "Số lượng phải lớn hơn 0")
    @Schema(description = "Số lượng theo đơn vị đã chọn", example = "10")
    private BigDecimal quantity;
}
