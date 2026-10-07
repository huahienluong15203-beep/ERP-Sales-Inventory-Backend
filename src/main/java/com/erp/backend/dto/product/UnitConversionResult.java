package com.erp.backend.dto.product;

import com.erp.backend.entity.UnitConversionSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Kết quả tính toán quy đổi đơn vị tính (S2-07).
 * Cung cấp:
 * - Số lượng quy đổi về đơn vị cơ sở chuẩn để ghi sổ kho / kế toán.
 * - Snapshot đầy đủ để lưu cố định vào giao dịch kho hoặc đơn hàng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả quy đổi đơn vị tính (S2-07)")
public class UnitConversionResult {

    @Schema(description = "ID sản phẩm", example = "1")
    private Long productId;

    @Schema(description = "Mã SKU", example = "SP-COCA-330")
    private String sku;

    @Schema(description = "Tên sản phẩm", example = "Nước ngọt Coca-Cola lon 330ml")
    private String productName;

    @Schema(description = "Đơn vị tính được chọn trên phiếu", example = "Thùng")
    private String inputUnit;

    @Schema(description = "Số lượng theo đơn vị trên phiếu", example = "10")
    private BigDecimal inputQuantity;

    @Schema(description = "Hệ số quy đổi chốt tại thời điểm tính", example = "24")
    private BigDecimal conversionFactor;

    @Schema(description = "Đơn vị tính cơ sở của SKU", example = "Lon")
    private String baseUnit;

    @Schema(description = "Số lượng quy đổi về đơn vị cơ sở ghi sổ kho", example = "240")
    private BigDecimal baseQuantity;

    @Schema(description = "Công thức / diễn giải quy đổi", example = "10 Thùng x 24 = 240 Lon")
    private String formula;

    @Schema(description = "Thời gian tính toán quy đổi")
    private LocalDateTime convertedAt;

    @Schema(description = "Snapshot đóng băng để lưu trực tiếp vào dòng chi tiết phiếu kho hoặc đơn hàng (S2-07 AC3)")
    private UnitConversionSnapshot snapshot;
}
