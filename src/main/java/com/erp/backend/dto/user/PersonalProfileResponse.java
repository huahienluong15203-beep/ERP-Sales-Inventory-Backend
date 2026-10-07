package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * S2-02: DTO phản hồi thông tin hồ sơ cá nhân của người dùng.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Thông tin hồ sơ cá nhân người dùng (S2-02)")
public class PersonalProfileResponse {

    @Schema(description = "ID người dùng", example = "1")
    private Long id;

    @Schema(description = "Tên đăng nhập (chỉ đọc)", example = "admin")
    private String username;

    @Schema(description = "Họ và tên", example = "Quản Trị Viên Hệ Thống")
    private String fullName;

    @Schema(description = "Email (chỉ đọc)", example = "okluon123pk@gmail.com")
    private String email;

    @Schema(description = "Số điện thoại liên hệ", example = "0988776655")
    private String phone;

    @Schema(description = "Đường dẫn ảnh đại diện đầy đủ (S2-03)", example = "/uploads/avatars/avatar_1_1712345678.png")
    private String avatarUrl;

    @Schema(description = "Đường dẫn ảnh đại diện thu nhỏ (thumbnail S2-03)", example = "/uploads/avatars/avatar_1_1712345678_thumb.png")
    private String avatarThumbnailUrl;

    @Schema(description = "Trạng thái tài khoản", example = "ACTIVE")
    private String status;

    @Schema(description = "Danh sách vai trò được cấp (chỉ đọc)")
    private List<String> roles;

    @Schema(description = "Danh sách kho trực thuộc (chỉ đọc)")
    private List<RefItem> warehouses;

    @Schema(description = "Danh sách địa bàn phụ trách (chỉ đọc)")
    private List<RefItem> regions;

    @Schema(description = "Thời điểm tạo tài khoản")
    private LocalDateTime createdAt;

    @Schema(description = "Thời điểm cập nhật gần nhất")
    private LocalDateTime updatedAt;
}
