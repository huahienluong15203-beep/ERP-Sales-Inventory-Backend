package com.erp.backend.dto.product;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Báo cáo tổng kết kết quả thực hiện nhập danh mục sản phẩm hàng loạt (S2-08).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Báo cáo tổng kết kết quả nhập sản phẩm từ Excel (S2-08)")
public class ProductImportSummaryResponse {

    @Schema(description = "Tổng số dòng trong tệp Excel", example = "5000")
    private int totalRows;

    @Schema(description = "Số dòng xử lý thành công (tạo mới + cập nhật)", example = "4980")
    private int successCount;

    @Schema(description = "Số sản phẩm được tạo mới thành công", example = "4000")
    private int createdCount;

    @Schema(description = "Số sản phẩm được cập nhật thành công (SKU đã tồn tại)", example = "980")
    private int updatedCount;

    @Schema(description = "Số dòng lỗi bị bỏ qua", example = "20")
    private int errorCount;

    @Schema(description = "Thời điểm thực thi nhập dữ liệu")
    private LocalDateTime importedAt;

    @Schema(description = "Thông điệp tóm tắt kết quả", example = "Đã nhập thành công 4.980 sản phẩm (4.000 tạo mới, 980 cập nhật), 20 dòng lỗi bị bỏ qua.")
    private String message;

    @Schema(description = "Danh sách chi tiết các dòng bị lỗi kèm lý do cụ thể")
    @Builder.Default
    private List<ProductImportRowDto> errorRows = new ArrayList<>();
}
