package com.erp.backend.service;

import com.erp.backend.dto.user.AvatarUploadResponse;
import com.erp.backend.dto.user.PersonalProfileResponse;
import com.erp.backend.dto.user.RefItem;
import com.erp.backend.dto.user.UpdatePersonalProfileRequest;
import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;

/**
 * Service xử lý nghiệp vụ Hồ sơ cá nhân (S2-02, S2-03).
 */
@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final AvatarStorageService avatarStorageService;

    private static final List<RoleName> ROLE_PRIORITY_ORDER = List.of(
            RoleName.ROLE_ADMIN,
            RoleName.ROLE_SALES_MANAGER,
            RoleName.ROLE_WH_MANAGER,
            RoleName.ROLE_ACCOUNTANT,
            RoleName.ROLE_WAREHOUSE,
            RoleName.ROLE_SALES_REP,
            RoleName.ROLE_CUSTOMER
    );

    @Transactional(readOnly = true)
    public PersonalProfileResponse getProfile(Long userId) {
        User user = findUserById(userId);
        return toProfileResponse(user);
    }

    @Transactional
    public PersonalProfileResponse updateProfile(Long userId, UpdatePersonalProfileRequest req) {
        User user = findUserById(userId);

        String normalizedPhone = normalizePhone(req.getPhone());

        // Kiểm tra trùng lặp số điện thoại với tài khoản khác
        if (StringUtils.hasText(normalizedPhone) && userRepository.existsByPhoneAndIdNot(normalizedPhone, userId)) {
            throw BusinessException.conflict(
                    "PHONE_EXISTS",
                    "Số điện thoại '" + normalizedPhone + "' đã được sử dụng bởi một tài khoản khác trong hệ thống.",
                    "phone"
            );
        }

        // Cập nhật thông tin được phép (Họ tên và Số điện thoại)
        user.setFullName(req.getFullName().trim());
        user.setPhone(normalizedPhone);

        // Bảo mật bất biến: KHÔNG CHO PHÉP sửa username, email, roles, warehouses, regions
        User saved = userRepository.save(user);
        return toProfileResponse(saved);
    }

    /**
     * S2-03: Tải lên và cập nhật ảnh đại diện người dùng.
     * Hỗ trợ ảnh JPG/PNG tối đa 2MB, hỗ trợ cắt vuông (tự động căn giữa hoặc theo toạ độ)
     * và tạo bản thu nhỏ (thumbnail).
     */
    @Transactional
    public AvatarUploadResponse uploadAvatar(Long userId, MultipartFile file, Integer x, Integer y, Integer width, Integer height) {
        User user = findUserById(userId);

        // Lưu trữ avatar mới và tạo thumbnail
        AvatarStorageService.AvatarResult result = avatarStorageService.processAndStoreAvatar(userId, file, x, y, width, height);

        // Dọn dẹp tệp ảnh đại diện cũ nếu có
        String oldAvatar = user.getAvatarUrl();
        String oldThumb = user.getAvatarThumbnailUrl();
        if (StringUtils.hasText(oldAvatar) || StringUtils.hasText(oldThumb)) {
            avatarStorageService.deleteAvatarFiles(oldAvatar, oldThumb);
        }

        user.setAvatarUrl(result.avatarUrl());
        user.setAvatarThumbnailUrl(result.avatarThumbnailUrl());
        User saved = userRepository.save(user);

        return AvatarUploadResponse.builder()
                .message("Tải ảnh đại diện thành công")
                .avatarUrl(result.avatarUrl())
                .avatarThumbnailUrl(result.avatarThumbnailUrl())
                .profile(toProfileResponse(saved))
                .build();
    }

    /**
     * S2-03: Xoá ảnh đại diện (trở về ảnh mặc định).
     */
    @Transactional
    public PersonalProfileResponse removeAvatar(Long userId) {
        User user = findUserById(userId);

        if (StringUtils.hasText(user.getAvatarUrl()) || StringUtils.hasText(user.getAvatarThumbnailUrl())) {
            avatarStorageService.deleteAvatarFiles(user.getAvatarUrl(), user.getAvatarThumbnailUrl());
            user.setAvatarUrl(null);
            user.setAvatarThumbnailUrl(null);
            user = userRepository.save(user);
        }

        return toProfileResponse(user);
    }

    private User findUserById(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy thông tin tài khoản người dùng id = " + userId));
    }

    /**
     * Chuẩn hoá số điện thoại Việt Nam: chuyển +84 thành 0.
     */
    private String normalizePhone(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("\\s+", "");
        if (cleaned.startsWith("+84")) {
            cleaned = "0" + cleaned.substring(3);
        }
        return cleaned;
    }

    private PersonalProfileResponse toProfileResponse(User u) {
        List<String> sortedRoles = u.getRoles().stream()
                .map(Role::getName)
                .sorted(Comparator.comparingInt(r -> {
                    int idx = ROLE_PRIORITY_ORDER.indexOf(r);
                    return idx == -1 ? 99 : idx;
                }))
                .map(Enum::name)
                .toList();

        List<RefItem> warehouses = u.getWarehouses() != null
                ? u.getWarehouses().stream()
                .map(w -> new RefItem(w.getId(), w.getCode(), w.getName()))
                .sorted(Comparator.comparing(RefItem::code))
                .toList()
                : List.of();

        List<RefItem> regions = u.getRegions() != null
                ? u.getRegions().stream()
                .map(r -> new RefItem(r.getId(), r.getCode(), r.getName()))
                .sorted(Comparator.comparing(RefItem::code))
                .toList()
                : List.of();

        return PersonalProfileResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .avatarUrl(u.getAvatarUrl())
                .avatarThumbnailUrl(u.getAvatarThumbnailUrl())
                .status(u.getStatus())
                .roles(sortedRoles)
                .warehouses(warehouses)
                .regions(regions)
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .build();
    }
}
