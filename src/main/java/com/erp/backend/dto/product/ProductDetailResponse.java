package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO phản hồi chi tiết sản phẩm kèm toàn bộ đơn vị quy đổi (S2-05 & S2-07).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Chi tiết sản phẩm và danh sách đơn vị quy đổi (S2-05, S2-07)")
public class ProductDetailResponse {

    private Long id;
    private String sku;
    private String name;
    private String category;

    // S2-06: id nhóm hàng trong cây (null nếu chưa gắn vào cây)
    private Long categoryId;
    private String baseUnit;
    private String packaging;
    private BigDecimal costPrice;
    private String status;
    private String barcode;
    private String imageUrl;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Schema(description = "Danh sách các đơn vị quy đổi đã khai báo (Thùng, Lốc...)")
    @Builder.Default
    private List<ProductUnitConversionResponse> unitConversions = new ArrayList<>();

    @Schema(description = "Toàn bộ đơn vị tính khả dụng (Bao gồm đơn vị cơ sở hệ số 1 và các đơn vị quy đổi)")
    @Builder.Default
    private List<ProductUnitConversionResponse> allUnits = new ArrayList<>();
}
