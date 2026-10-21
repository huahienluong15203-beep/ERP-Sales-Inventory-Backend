package com.erp.backend.dto.product;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * DTO đại diện cho 1 dòng dữ liệu đọc từ tệp Excel sản phẩm (S2-08).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Chi tiết một dòng sản phẩm đọc từ tệp Excel (S2-08)")
public class ProductImportRowDto {

    @Schema(description = "Số thứ tự dòng trong file Excel", example = "2")
    private int rowNumber;

    @Schema(description = "Mã SKU duy nhất của sản phẩm", example = "SP-BEER-330")
    private String sku;

    @Schema(description = "Tên sản phẩm", example = "Bia Heineken lon 330ml")
    private String name;

    @Schema(description = "Nhóm hàng / Ngành hàng", example = "Đồ uống")
    private String category;

    @Schema(description = "Ngành hàng (Cấp 1)", example = "Đồ uống")
    private String department;

    @Schema(description = "Phân nhóm (Cấp 3)", example = "Có ga")
    private String subCategory;

    @Schema(description = "Đường dẫn phân cấp trong cây", example = "Đồ uống > Nước giải khát > Có ga")
    private String categoryPath;

    @Schema(description = "Cấp bậc trong cây phân cấp", example = "3")
    private Integer categoryLevel;

    @Schema(description = "Đánh dấu sản phẩm được đổi cấp trong cây phân cấp khi ghi đè", example = "true")
    private boolean levelChanged;

    @Schema(description = "Đơn vị tính cơ sở", example = "Lon")
    private String baseUnit;

    @Schema(description = "Quy cách đóng gói", example = "Thùng 24 lon")
    private String packaging;

    @Schema(description = "Giá vốn (VNĐ)", example = "380000")
    private BigDecimal costPrice;

    @Schema(description = "Mã vạch (Barcode / EAN-13)", example = "8934567890123")
    private String barcode;

    @Schema(description = "Trạng thái kinh doanh (ACTIVE / INACTIVE)", example = "ACTIVE")
    private String status;

    @Schema(description = "Mô tả / ghi chú sản phẩm", example = "Sản phẩm bán chạy dịp Tết")
    private String description;

    @Schema(description = "Hành động dự kiến: CREATE (Tạo mới) hoặc UPDATE (Cập nhật sản phẩm đã tồn tại)", example = "UPDATE")
    private String action;

    @JsonProperty("isUpdate")
    @Schema(description = "Đánh dấu SKU này đã tồn tại trong hệ thống (S2-08 AC2)", example = "true")
    private boolean isUpdate;

    @Schema(description = "Dòng này có hợp lệ không", example = "true")
    private boolean valid;

    @Schema(description = "Danh sách lỗi kiểm tra tính hợp lệ trên dòng này (nếu có)")
    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
