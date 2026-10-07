package com.erp.backend.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * S2-02: DTO cập nhật hồ sơ cá nhân.
 * Chỉ cho phép người dùng tự sửa Họ tên và Số điện thoại.
 * Các trường username, email, vai trò, kho, địa bàn bị cấm sửa đổi từ client.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Yêu cầu cập nhật hồ sơ cá nhân (S2-02)")
public class UpdatePersonalProfileRequest {

    @NotBlank(message = "Họ tên không được để trống")
    @Size(min = 2, max = 100, message = "Họ tên phải từ 2 đến 100 ký tự")
    @Schema(description = "Họ và tên người dùng", example = "Lê Hồng Phong")
    private String fullName;

    @Pattern(regexp = "^$|^(0|\\+84)(3|5|7|8|9)\\d{8}$", message = "Số điện thoại Việt Nam không hợp lệ (10 số, bắt đầu bằng 03, 05, 07, 08, 09)")
    @Schema(description = "Số điện thoại di động Việt Nam", example = "0987654321")
    private String phone;
}
