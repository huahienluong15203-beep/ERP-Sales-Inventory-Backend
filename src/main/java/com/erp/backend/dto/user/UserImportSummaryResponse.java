package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Báo cáo tổng kết kết quả thực hiện nhập danh sách người dùng hàng loạt (S2-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Báo cáo tổng kết sau khi nhập người dùng từ Excel")
public class UserImportSummaryResponse {

    @Schema(description = "Tổng số dòng đã xử lý", example = "25")
    private int totalProcessed;

    @Schema(description = "Số tài khoản tạo thành công", example = "22")
    private int successCount;

    @Schema(description = "Số dòng bị bỏ qua do lỗi", example = "3")
    private int failedCount;

    @Schema(description = "Danh sách tài khoản đã tạo thành công")
    @Builder.Default
    private List<CreatedUserItem> createdUsers = new ArrayList<>();

    @Schema(description = "Danh sách dòng bị bỏ qua kèm lý do")
    @Builder.Default
    private List<FailedUserRow> failedRows = new ArrayList<>();

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreatedUserItem {
        private Long id;
        private String username;
        private String fullName;
        private String email;
        private String phone;
        private List<String> roles;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class FailedUserRow {
        private int rowNumber;
        private String username;
        private String email;
        private List<String> reasons;
    }
}
