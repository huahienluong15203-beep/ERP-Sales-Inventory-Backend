package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

/**
 * DTO phản hồi kết quả tải ảnh đại diện lên hệ thống (S2-03).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Kết quả tải ảnh đại diện (S2-03)")
public class AvatarUploadResponse {

    @Schema(description = "Thông báo kết quả", example = "Tải ảnh đại diện thành công")
    private String message;

    @Schema(description = "Đường dẫn ảnh đại diện chuẩn vuông", example = "/uploads/avatars/avatar_1_1712345678_abcd.png")
    private String avatarUrl;

    @Schema(description = "Đường dẫn ảnh đại diện thu nhỏ (thumbnail)", example = "/uploads/avatars/avatar_1_1712345678_abcd_thumb.png")
    private String avatarThumbnailUrl;

    @Schema(description = "Hồ sơ cá nhân sau khi cập nhật avatar")
    private PersonalProfileResponse profile;
}
