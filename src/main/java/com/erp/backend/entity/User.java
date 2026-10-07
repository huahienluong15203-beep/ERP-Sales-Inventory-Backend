package com.erp.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false)
    private String password; // Lưu mật khẩu đã mã hoá BCrypt

    @Column(nullable = false, length = 100)
    private String fullName;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 20)
    private String phone;

    // Trạng thái tài khoản: ACTIVE (Hoạt động), LOCKED (Khoá)
    @Column(length = 30, nullable = false)
    @Builder.Default
    private String status = "ACTIVE";

    @Column(name = "lock_reason", length = 500)
    private String lockReason;

    // Phục vụ S1-01: Đếm số lần đăng nhập sai (sai 5 lần liên tiếp)
    @Column(name = "failed_login_attempts", nullable = false)
    @Builder.Default
    private int failedLoginAttempts = 0;

    // Phục vụ S1-01: Thời điểm hết hạn khoá tạm 15 phút
    @Column(name = "lock_until")
    private LocalDateTime lockUntil;

    // Phục vụ S1-05: Phân quyền theo 7 vai trò (1 user có thể có nhiều vai trò)
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "role_id"))
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    // S1-09: Người dùng thuộc vai trò kho phải gắn với ít nhất 1 kho
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_warehouses", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "warehouse_id"))
    @Builder.Default
    private Set<Warehouse> warehouses = new HashSet<>();

    // S1-09: Địa bàn phụ trách (chủ yếu cho nhân viên / quản lý kinh doanh)
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_regions", joinColumns = @JoinColumn(name = "user_id"), inverseJoinColumns = @JoinColumn(name = "region_id"))
    @Builder.Default
    private Set<Region> regions = new HashSet<>();

    // S1-08: Tài khoản mới tạo dùng mật khẩu tạm -> bắt buộc đổi ở lần đăng nhập đầu (S1-04 xử lý)
    // columnDefinition có DEFAULT để ddl-auto=update thêm cột được trên bảng đã có dữ liệu
    @Column(name = "must_change_password", nullable = false, columnDefinition = "boolean default false")
    @Builder.Default
    private boolean mustChangePassword = false;

    // S1-04: Thời điểm đổi mật khẩu — dùng để vô hiệu hoá token cũ (thu hồi phiên khác)
    @Column(name = "password_changed_at")
    private LocalDateTime passwordChangedAt;

    // Quản lý đơn phiên (Single Active Session): Mỗi tài khoản chỉ có duy nhất 1 phiên hoạt động
    @Column(name = "active_session_id", length = 100)
    private String activeSessionId;

    // S2-03: Ảnh đại diện người dùng (hỗ trợ hiển thị trên lịch sử, đơn hàng, navbar)
    @Column(name = "avatar_url", length = 500)
    private String avatarUrl;

    // S2-03: Ảnh đại diện bản thu nhỏ (thumbnail) tối ưu tải danh sách/lịch sử
    @Column(name = "avatar_thumbnail_url", length = 500)
    private String avatarThumbnailUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
