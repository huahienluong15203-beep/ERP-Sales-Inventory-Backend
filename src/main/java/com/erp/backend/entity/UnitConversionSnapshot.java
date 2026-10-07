package com.erp.backend.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.math.BigDecimal;

/**
 * Snapshot đơn vị tính và hệ số quy đổi tại thời điểm phát sinh giao dịch (S2-07).
 * Được nhúng (@Embedded) vào các dòng chi tiết giao dịch (phiếu nhập/xuất kho, chi tiết đơn hàng).
 * Đảm bảo: Khi đổi hệ số quy đổi ở bảng master, các giao dịch đã ghi sổ trước đó
 * KHÔNG BAO GIỜ bị thay đổi hoặc sai lệch số lượng cơ sở.
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Snapshot thông tin đơn vị quy đổi tại thời điểm ghi sổ giao dịch (S2-07)")
public class UnitConversionSnapshot {

    // Đơn vị người dùng nhập/chọn trên phiếu (vd: "Thùng", "Lốc", "Lon")
    @Column(name = "transaction_unit", length = 50)
    @Schema(description = "Đơn vị tính được chọn trên phiếu", example = "Thùng")
    private String transactionUnit;

    // Số lượng theo đơn vị trên phiếu (vd: 10 Thùng)
    @Column(name = "transaction_quantity", precision = 12, scale = 4)
    @Schema(description = "Số lượng theo đơn vị phiếu", example = "10")
    private BigDecimal transactionQuantity;

    // Hệ số quy đổi tại thời điểm ghi sổ (vd: 24.0000)
    @Column(name = "conversion_factor", precision = 12, scale = 4)
    @Schema(description = "Hệ số quy đổi chốt tại thời điểm giao dịch", example = "24")
    private BigDecimal conversionFactor;

    // Đơn vị tính cơ sở của SKU (vd: "Lon")
    @Column(name = "base_unit", length = 30)
    @Schema(description = "Đơn vị tính cơ sở chuẩn của SKU", example = "Lon")
    private String baseUnit;

    // Số lượng quy đổi về đơn vị cơ sở chốt ghi sổ (vd: 240.0000 Lon = 10 * 24)
    @Column(name = "base_quantity", precision = 12, scale = 4)
    @Schema(description = "Số lượng quy đổi về đơn vị cơ sở", example = "240")
    private BigDecimal baseQuantity;
}
