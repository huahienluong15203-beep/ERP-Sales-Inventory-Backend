package com.erp.backend.dto.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * DTO trả về thông tin đơn vị tính của sản phẩm.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin đơn vị tính của sản phẩm")
public class ProductUnitConversionResponse {

    @Schema(description = "ID bản ghi quy đổi (null nếu là đơn vị cơ sở)", example = "1")
    private Long id;

    @Schema(description = "ID sản phẩm", example = "10")
    private Long productId;

    @Schema(description = "Mã SKU", example = "SP-COCA-330")
    private String sku;

    @Schema(description = "Tên đơn vị tính (Lon, Lốc, Thùng...)", example = "Thùng")
    private String unitName;

    @Schema(description = "Hệ số quy đổi về đơn vị cơ sở", example = "24")
    private BigDecimal conversionFactor;

    @Schema(description = "Có phải đơn vị tính cơ sở không (hệ số = 1.0)", example = "false")
    private boolean isBaseUnit;

    @Schema(description = "Công thức quy đổi trực quan", example = "1 Thùng = 24 Lon")
    private String formula;

    @Schema(description = "Mã vạch riêng của đơn vị", example = "8934567890123")
    private String barcode;

    @Schema(description = "Đơn vị mặc định khi nhập kho", example = "true")
    private Boolean isDefaultPurchase;

    @Schema(description = "Đơn vị mặc định khi xuất bán", example = "false")
    private Boolean isDefaultSale;

    @Schema(description = "Mô tả / ghi chú")
    private String description;

    @Schema(description = "Trạng thái áp dụng (ACTIVE / INACTIVE)", example = "ACTIVE")
    private String status;

    @Schema(description = "Thời gian tạo")
    private LocalDateTime createdAt;

    @Schema(description = "Thời gian cập nhật")
    private LocalDateTime updatedAt;
}
