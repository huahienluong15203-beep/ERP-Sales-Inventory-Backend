package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO đại diện cho 1 dòng dữ liệu đọc từ tệp Excel người dùng (S2-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Chi tiết một dòng dữ liệu import từ Excel")
public class UserImportRowDto {

    @Schema(description = "Số thứ tự dòng trong file Excel (bắt đầu từ dòng 2)", example = "2")
    private int rowNumber;

    @Schema(description = "Tên đăng nhập", example = "nguyenvanan")
    private String username;

    @Schema(description = "Họ và tên", example = "Nguyễn Văn An")
    private String fullName;

    @Schema(description = "Email", example = "an.nv@erp.com")
    private String email;

    @Schema(description = "Số điện thoại", example = "0987654321")
    private String phone;

    @Schema(description = "Danh sách mã vai trò", example = "[\"ROLE_SALES_REP\"]")
    @Builder.Default
    private List<String> roles = new ArrayList<>();

    @Schema(description = "Danh sách mã kho", example = "[\"WH-MB01\"]")
    @Builder.Default
    private List<String> warehouseCodes = new ArrayList<>();

    @Schema(description = "Danh sách mã địa bàn", example = "[\"MB\"]")
    @Builder.Default
    private List<String> regionCodes = new ArrayList<>();

    @Schema(description = "Trạng thái hợp lệ của dòng dữ liệu", example = "true")
    private boolean valid;

    @Schema(description = "Danh sách chi tiết lỗi nếu dòng không hợp lệ")
    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
