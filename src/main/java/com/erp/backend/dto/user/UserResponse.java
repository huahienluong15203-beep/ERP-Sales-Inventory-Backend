package com.erp.backend.dto.user;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/** Thông tin tài khoản trả về cho Frontend — KHÔNG bao giờ chứa mật khẩu. */
@Getter
@Builder
public class UserResponse {
    private Long id;
    private String username;
    private String fullName;
    private String email;
    private String phone;
    private String status;
    private String lockReason;
    private boolean handoverRequired;
    private boolean mustChangePassword;
    private List<String> roles;
    private List<RefItem> warehouses;
    private List<RefItem> regions;
    private String avatarUrl;
    private String avatarThumbnailUrl;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
