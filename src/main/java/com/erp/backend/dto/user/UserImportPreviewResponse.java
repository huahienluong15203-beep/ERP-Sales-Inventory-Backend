package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Phản hồi xem trước (Preview) dữ liệu từ tệp Excel (S2-01).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả xem trước và kiểm tra tính hợp lệ dữ liệu Import Excel")
public class UserImportPreviewResponse {

    @Schema(description = "Tên tệp Excel tải lên", example = "danh_sach_nhan_su.xlsx")
    private String fileName;

    @Schema(description = "Tổng số dòng dữ liệu đọc được", example = "25")
    private int totalRows;

    @Schema(description = "Số dòng hợp lệ sẵn sàng nhập", example = "22")
    private int validRowsCount;

    @Schema(description = "Số dòng phát hiện lỗi sẽ bị bỏ qua", example = "3")
    private int invalidRowsCount;

    @Schema(description = "Chi tiết danh sách các dòng dữ liệu kèm trạng thái hợp lệ và lỗi")
    @Builder.Default
    private List<UserImportRowDto> rows = new ArrayList<>();
}
