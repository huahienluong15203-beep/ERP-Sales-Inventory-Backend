package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Phản hồi xem trước (Preview) dữ liệu từ tệp Excel sản phẩm (S2-08).
 * Báo lỗi chi tiết theo từng dòng và phân định rõ CREATE / UPDATE cho từng SKU.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả xem trước tệp Excel nhập danh mục sản phẩm (S2-08)")
public class ProductImportPreviewResponse {

    @Schema(description = "Tổng số dòng dữ liệu đọc được từ file", example = "5000")
    private int totalRows;

    @Schema(description = "Số dòng hợp lệ (sẵn sàng nhập/cập nhật)", example = "4980")
    private int validRows;

    @Schema(description = "Số dòng không hợp lệ (bị lỗi)", example = "20")
    private int invalidRows;

    @Schema(description = "Số sản phẩm mới sẽ được tạo (SKU chưa tồn tại)", example = "4000")
    private int createCount;

    @Schema(description = "Số sản phẩm sẽ được cập nhật (SKU đã tồn tại trong hệ thống)", example = "980")
    private int updateCount;

    @Schema(description = "Danh sách chi tiết các dòng đã phân tích")
    @Builder.Default
    private List<ProductImportRowDto> rows = new ArrayList<>();
}
