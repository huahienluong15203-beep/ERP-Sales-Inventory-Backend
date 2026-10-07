package com.erp.backend.service;

import com.erp.backend.dto.LockUserRequest;
import com.erp.backend.dto.UserAccountResponse;
import com.erp.backend.dto.user.*;
import com.erp.backend.entity.*;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * S1-08: Tạo, sửa, tìm kiếm tài khoản người dùng.
 * S1-09: Gán vai trò, kho, địa bàn.
 * S1-10: Khóa / mở khóa tài khoản.
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    /** Các vai trò thuộc khối kho -> bắt buộc gắn ít nhất 1 kho (S1-09). */
    static final Set<RoleName> WAREHOUSE_ROLES = EnumSet.of(RoleName.ROLE_WAREHOUSE, RoleName.ROLE_WH_MANAGER);

    static final int DEFAULT_PAGE_SIZE = 20;
    static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final WarehouseRepository warehouseRepository;
    private final RegionRepository regionRepository;
    private final PasswordEncoder passwordEncoder;
    private final TempPasswordGenerator tempPasswordGenerator;
    private final MailService mailService;

    // ======================= S1-10: KHÓA / MỞ KHÓA TÀI KHOẢN =======================

    @Transactional(readOnly = true)
    public List<UserAccountResponse> getUsers() {
        return userRepository.findAll().stream()
                .map(this::toAccountResponse)
                .toList();
    }

    @Transactional
    public UserAccountResponse lockUser(Long userId, LockUserRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vui lòng nhập lý do khóa tài khoản.");
        }

        String reason = request.reason().trim();
        if (reason.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Lý do khóa không được vượt quá 500 ký tự.");
        }

        User user = findUser(userId);
        user.setStatus("LOCKED");
        user.setLockUntil(null);
        user.setLockReason(reason);
        user.setFailedLoginAttempts(0);
        user.setActiveSessionId(null); // Thu hồi ngay lập tức phiên làm việc hiện tại

        return toAccountResponse(userRepository.save(user));
    }

    @Transactional
    public UserAccountResponse unlockUser(Long userId) {
        User user = findUser(userId);
        user.setStatus("ACTIVE");
        user.setLockUntil(null);
        user.setFailedLoginAttempts(0);

        return toAccountResponse(userRepository.save(user));
    }

    private UserAccountResponse toAccountResponse(User user) {
        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName().name())
                .sorted()
                .toList();
        boolean salesEmployee = user.getRoles().stream()
                .map(Role::getName)
                .anyMatch(roleName -> roleName == RoleName.ROLE_SALES_REP || roleName == RoleName.ROLE_SALES_MANAGER);
        boolean handoverRequired = salesEmployee
                && "LOCKED".equalsIgnoreCase(user.getStatus())
                && user.getLockUntil() == null;

        return new UserAccountResponse(
                user.getId(),
                user.getUsername(),
                user.getFullName(),
                user.getEmail(),
                user.getStatus(),
                user.getLockReason(),
                user.getLockUntil(),
                roles,
                handoverRequired);
    }

    // ======================= S1-08: TÌM KIẾM / XEM =======================

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> search(String keyword, RoleName role, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        Page<User> result = userRepository.findAll(
                UserSpecifications.search(keyword, role, status),
                PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt")));

        return PageResponse.of(result.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public UserResponse getById(Long id) {
        return toResponse(findUser(id));
    }

    @Transactional(readOnly = true)
    public UserFormOptionsResponse getFormOptions() {
        List<String> roles = Arrays.stream(RoleName.values()).map(Enum::name).toList();
        List<RefItem> warehouses = warehouseRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .map(w -> new RefItem(w.getId(), w.getCode(), w.getName())).toList();
        List<RefItem> regions = regionRepository.findByStatusOrderByNameAsc("ACTIVE").stream()
                .map(r -> new RefItem(r.getId(), r.getCode(), r.getName())).toList();
        return new UserFormOptionsResponse(roles, warehouses, regions);
    }

    // ======================= S1-08: TẠO TÀI KHOẢN =======================

    @Transactional
    public CreateUserResponse create(CreateUserRequest req) {
        String username = req.getUsername().trim().toLowerCase();
        String email = req.getEmail().trim().toLowerCase();
        String phone = normalizePhone(req.getPhone());

        // Chặn trùng kèm thông báo cụ thể từng trường
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw BusinessException.conflict("USERNAME_EXISTS", "Tên tài khoản '" + username + "' đã tồn tại", "username");
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw BusinessException.conflict("EMAIL_EXISTS", "Email '" + email + "' đã được sử dụng", "email");
        }
        if (phone != null && userRepository.existsByPhone(phone)) {
            throw BusinessException.conflict("PHONE_EXISTS", "Số điện thoại '" + phone + "' đã được sử dụng", "phone");
        }

        String tempPassword = tempPasswordGenerator.generate();

        User user = User.builder()
                .username(username)
                .fullName(req.getFullName().trim())
                .email(email)
                .phone(phone)
                .password(passwordEncoder.encode(tempPassword))
                .status("ACTIVE")
                .failedLoginAttempts(0)
                .mustChangePassword(true)
                .build();

        // Người tạo là Admin khác -> không áp dụng quy tắc "tự gỡ quyền admin" (currentUserId = null)
        applyAssignments(user, req.getRoles(), req.getWarehouseIds(), req.getRegionIds(), null);

        User saved = userRepository.save(user);

        // Gửi email CHẠY NGẦM và chỉ gửi SAU KHI đã lưu DB thành công -> API trả kết quả ngay.
        boolean emailQueued = mailService.isConfigured();
        String toEmail = saved.getEmail();
        String fullName = saved.getFullName();
        String savedUsername = saved.getUsername();
        AfterCommit.run(() -> mailService.sendAccountCreatedEmail(toEmail, fullName, savedUsername, tempPassword));

        return new CreateUserResponse(toResponse(saved), emailQueued);
    }

    // ======================= S1-08: SỬA TÀI KHOẢN =======================

    @Transactional
    public UserResponse update(Long id, UpdateUserRequest req) {
        User user = findUser(id);
        String email = req.getEmail().trim().toLowerCase();
        String phone = normalizePhone(req.getPhone());

        if (userRepository.existsByEmailIgnoreCaseAndIdNot(email, id)) {
            throw BusinessException.conflict("EMAIL_EXISTS", "Email '" + email + "' đã được sử dụng", "email");
        }
        if (phone != null && userRepository.existsByPhoneAndIdNot(phone, id)) {
            throw BusinessException.conflict("PHONE_EXISTS", "Số điện thoại '" + phone + "' đã được sử dụng", "phone");
        }

        user.setFullName(req.getFullName().trim());
        user.setEmail(email);
        user.setPhone(phone);
        return toResponse(userRepository.save(user));
    }

    // ======================= S1-09: GÁN VAI TRÒ / KHO / ĐỊA BÀN =======================

    @Transactional
    public UserResponse updateAssignments(Long id, UserAssignmentRequest req, Long currentUserId) {
        User user = findUser(id);
        applyAssignments(user, req.getRoles(), req.getWarehouseIds(), req.getRegionIds(), currentUserId);
        return toResponse(userRepository.save(user));
    }

    /**
     * Áp dụng quy tắc S1-09:
     * 1. Một người có thể giữ nhiều vai trò, nhưng phải có ít nhất 1 vai trò.
     * 2. Vai trò kho (WAREHOUSE, WH_MANAGER) phải gắn ít nhất 1 kho.
     * 3. Admin không tự thu hồi vai trò ADMIN của chính mình.
     */
    void applyAssignments(User user, Set<RoleName> roleNames, Set<Long> warehouseIds,
                          Set<Long> regionIds, Long currentUserId) {
        Set<RoleName> roles = roleNames == null ? Set.of() : roleNames;
        Set<Long> whIds = warehouseIds == null ? Set.of() : warehouseIds;
        Set<Long> rgIds = regionIds == null ? Set.of() : regionIds;

        if (roles.isEmpty()) {
            throw BusinessException.badRequest("ROLE_REQUIRED", "Phải chọn ít nhất một vai trò");
        }

        boolean isSelf = currentUserId != null && currentUserId.equals(user.getId());
        boolean currentlyAdmin = user.getRoles().stream().anyMatch(r -> r.getName() == RoleName.ROLE_ADMIN);
        if (isSelf && currentlyAdmin && !roles.contains(RoleName.ROLE_ADMIN)) {
            throw BusinessException.badRequest("CANNOT_REVOKE_OWN_ADMIN",
                    "Không thể tự thu hồi vai trò Quản trị hệ thống của chính mình");
        }

        boolean hasWarehouseRole = roles.stream().anyMatch(WAREHOUSE_ROLES::contains);
        if (hasWarehouseRole && whIds.isEmpty()) {
            throw BusinessException.badRequest("WAREHOUSE_REQUIRED",
                    "Người dùng thuộc vai trò kho phải được gắn với ít nhất một kho");
        }
        if (!hasWarehouseRole) {
            whIds = Set.of();
        }

        boolean hasSalesRole = roles.stream().anyMatch(r -> r == RoleName.ROLE_SALES_REP || r == RoleName.ROLE_SALES_MANAGER);
        if (!hasSalesRole) {
            rgIds = Set.of();
        }

        List<Role> roleEntities = roleRepository.findByNameIn(roles);
        if (roleEntities.size() != roles.size()) {
            throw BusinessException.badRequest("ROLE_NOT_FOUND", "Có vai trò không tồn tại trong hệ thống");
        }

        List<Warehouse> warehouses = whIds.isEmpty() ? List.of() : warehouseRepository.findAllById(whIds);
        if (warehouses.size() != whIds.size()
                || warehouses.stream().anyMatch(w -> !"ACTIVE".equals(w.getStatus()))) {
            throw BusinessException.badRequest("WAREHOUSE_INVALID", "Có kho không tồn tại hoặc đã ngừng hoạt động");
        }

        List<Region> regions = rgIds.isEmpty() ? List.of() : regionRepository.findAllById(rgIds);
        if (regions.size() != rgIds.size()
                || regions.stream().anyMatch(r -> !"ACTIVE".equals(r.getStatus()))) {
            throw BusinessException.badRequest("REGION_INVALID", "Có địa bàn không tồn tại hoặc đã ngừng hoạt động");
        }

        if (user.getRoles() == null) {
            user.setRoles(new HashSet<>(roleEntities));
        } else {
            user.getRoles().clear();
            user.getRoles().addAll(roleEntities);
        }

        if (user.getWarehouses() == null) {
            user.setWarehouses(new HashSet<>(warehouses));
        } else {
            user.getWarehouses().clear();
            user.getWarehouses().addAll(warehouses);
        }

        if (user.getRegions() == null) {
            user.setRegions(new HashSet<>(regions));
        } else {
            user.getRegions().clear();
            user.getRegions().addAll(regions);
        }
    }

    // ======================= HÀM PHỤ =======================

    private static final List<RoleName> ROLE_PRIORITY_ORDER = List.of(
            RoleName.ROLE_ADMIN,
            RoleName.ROLE_SALES_MANAGER,
            RoleName.ROLE_WH_MANAGER,
            RoleName.ROLE_ACCOUNTANT,
            RoleName.ROLE_WAREHOUSE,
            RoleName.ROLE_SALES_REP,
            RoleName.ROLE_CUSTOMER
    );

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> BusinessException.notFound("Không tìm thấy tài khoản với id = " + id));
    }

    private String normalizePhone(String phone) {
        return StringUtils.hasText(phone) ? phone.trim() : null;
    }

    UserResponse toResponse(User u) {
        boolean salesEmployee = u.getRoles().stream()
                .map(Role::getName)
                .anyMatch(roleName -> roleName == RoleName.ROLE_SALES_REP || roleName == RoleName.ROLE_SALES_MANAGER);
        boolean handoverRequired = salesEmployee
                && "LOCKED".equalsIgnoreCase(u.getStatus())
                && u.getLockUntil() == null;

        List<String> sortedRoles = u.getRoles().stream()
                .map(Role::getName)
                .sorted(Comparator.comparingInt(r -> {
                    int idx = ROLE_PRIORITY_ORDER.indexOf(r);
                    return idx == -1 ? 99 : idx;
                }))
                .map(Enum::name)
                .toList();

        return UserResponse.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .email(u.getEmail())
                .phone(u.getPhone())
                .status(u.getStatus())
                .lockReason(u.getLockReason())
                .handoverRequired(handoverRequired)
                .mustChangePassword(u.isMustChangePassword())
                .roles(sortedRoles)
                .warehouses(u.getWarehouses().stream()
                        .map(w -> new RefItem(w.getId(), w.getCode(), w.getName()))
                        .sorted(Comparator.comparing(RefItem::code)).toList())
                .regions(u.getRegions().stream()
                        .map(r -> new RefItem(r.getId(), r.getCode(), r.getName()))
                        .sorted(Comparator.comparing(RefItem::code)).toList())
                .avatarUrl(u.getAvatarUrl())
                .avatarThumbnailUrl(u.getAvatarThumbnailUrl())
                .createdAt(u.getCreatedAt())
                .updatedAt(u.getUpdatedAt())
                .build();
    }
}
