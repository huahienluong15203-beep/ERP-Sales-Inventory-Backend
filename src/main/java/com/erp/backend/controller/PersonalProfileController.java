package com.erp.backend.controller;

import com.erp.backend.dto.user.AvatarUploadResponse;
import com.erp.backend.dto.user.PersonalProfileResponse;
import com.erp.backend.dto.user.UpdatePersonalProfileRequest;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.UserProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Controller xử lý xem và cập nhật hồ sơ cá nhân (S2-02) và ảnh đại diện (S2-03).
 * Dành cho mọi người dùng đã đăng nhập trong hệ thống (Tất cả 7 vai trò).
 */
@RestController
@RequestMapping("/api/v1/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "API Quản lý hồ sơ cá nhân người dùng (S2-02, S2-03)")
public class PersonalProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    @Operation(summary = "Xem hồ sơ cá nhân", description = "Lấy thông tin tài khoản của chính người dùng đang đăng nhập")
    public ResponseEntity<PersonalProfileResponse> getMyProfile(
            @AuthenticationPrincipal UserDetailsImpl userDetails) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }
        return ResponseEntity.ok(userProfileService.getProfile(userDetails.getId()));
    }

    @PutMapping
    @Operation(summary = "Cập nhật hồ sơ cá nhân", description = "Cập nhật Họ tên và Số điện thoại (kiểm tra định dạng VN). Không thể tự đổi username, email, vai trò, kho, địa bàn.")
    public ResponseEntity<PersonalProfileResponse> updateMyProfile(
            @AuthenticationPrincipal UserDetailsImpl userDetails,
            @Valid @RequestBody UpdatePersonalProfileRequest request) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }
        return ResponseEntity.ok(userProfileService.updateProfile(userDetails.getId(), request));
    }

    @PostMapping(value = "/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Tải lên ảnh đại diện (S2-03)", description = "Chấp nhận file JPG/PNG tối đa 2MB. Tự động cắt vuông hoặc cắt vuông theo toạ độ (x, y, width, height) và tạo thumbnail.")
    public ResponseEntity<AvatarUploadResponse> uploadMyAvatar(
            @AuthenticationPrincipal UserDetailsImpl userDetails,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "x", required = false) Integer x,
            @RequestParam(value = "y", required = false) Integer y,
            @RequestParam(value = "width", required = false) Integer width,
            @RequestParam(value = "height", required = false) Integer height) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }
        return ResponseEntity.ok(userProfileService.uploadAvatar(userDetails.getId(), file, x, y, width, height));
    }

    @DeleteMapping("/avatar")
    @Operation(summary = "Xoá ảnh đại diện (S2-03)", description = "Xoá ảnh đại diện hiện tại và trở về ảnh mặc định")
    public ResponseEntity<PersonalProfileResponse> removeMyAvatar(
            @AuthenticationPrincipal UserDetailsImpl userDetails) {
        if (userDetails == null || userDetails.getId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.");
        }
        return ResponseEntity.ok(userProfileService.removeAvatar(userDetails.getId()));
    }
}
