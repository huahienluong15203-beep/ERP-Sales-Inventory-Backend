package com.erp.backend.controller;

import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.entity.Region;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.UserDetailsImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/navigation")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserRepository userRepository;

    /**
     * API trả về thông tin người dùng và danh sách menu theo quyền (SCRUM-7 / S1-06)
     * - Trả về người dùng thực tế đang đăng nhập (hoặc tài khoản tương ứng khi đổi role)
     * - Tuyệt đối không fix cứng tên người dùng
     * - Menu phân quyền theo Role
     */
    @GetMapping("/user-context")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getUserNavigationContext(
            @RequestParam(required = false) String role,
            @AuthenticationPrincipal UserDetailsImpl userDetails) {

        Map<String, Object> response = new HashMap<>();
        Map<String, Object> userInfo = new HashMap<>();

        // Bắt buộc phải có phiên đăng nhập hợp lệ (Single Active Session)
        if (userDetails == null || userDetails.getId() == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        User user = userRepository.findById(userDetails.getId()).orElse(null);
        if (user == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED).build();
        }

        List<String> roleOrder = List.of(
                "ROLE_ADMIN", "ROLE_SALES_MANAGER", "ROLE_WH_MANAGER",
                "ROLE_ACCOUNTANT", "ROLE_WAREHOUSE", "ROLE_SALES_REP", "ROLE_CUSTOMER"
        );

        String effectiveRole = role;
        List<String> userRoles = List.of();
        if (user != null) {
            userRoles = user.getRoles().stream()
                    .map(r -> r.getName().name())
                    .sorted((a, b) -> {
                        int ia = roleOrder.indexOf(a);
                        int ib = roleOrder.indexOf(b);
                        if (ia < 0) ia = roleOrder.size();
                        if (ib < 0) ib = roleOrder.size();
                        return ia - ib;
                    })
                    .toList();
            if (effectiveRole == null || effectiveRole.isBlank() || !userRoles.contains(effectiveRole.toUpperCase())) {
                effectiveRole = userRoles.isEmpty() ? "ROLE_ADMIN" : userRoles.get(0);
            }

            userInfo.put("id", user.getId());
            userInfo.put("username", user.getUsername());
            userInfo.put("fullName", user.getFullName());
            userInfo.put("email", user.getEmail());
            userInfo.put("phone", user.getPhone() != null ? user.getPhone() : "0988776655");
            userInfo.put("avatarUrl", user.getAvatarUrl());
            userInfo.put("avatarThumbnailUrl", user.getAvatarThumbnailUrl());
            userInfo.put("status", user.getStatus());
            userInfo.put("role", effectiveRole);
            userInfo.put("roles", userRoles);
            userInfo.put("mustChangePassword", user.isMustChangePassword());

            String warehouseStr = (user.getWarehouses() != null && !user.getWarehouses().isEmpty())
                    ? user.getWarehouses().stream().map(Warehouse::getName).collect(Collectors.joining(", "))
                    : getDefaultWarehouseForRole(effectiveRole);
            userInfo.put("warehouse", warehouseStr);

            String locationStr = (user.getRegions() != null && !user.getRegions().isEmpty())
                    ? user.getRegions().stream().map(Region::getName).collect(Collectors.joining(", "))
                    : getDefaultLocationForRole(effectiveRole);
            userInfo.put("workLocation", locationStr);
        } else {
            if (effectiveRole == null || effectiveRole.isBlank()) {
                effectiveRole = "ROLE_ADMIN";
            }
            userInfo.put("id", 1L);
            userInfo.put("username", getSampleUsernameByRole(effectiveRole));
            userInfo.put("fullName", getSampleFullNameByRole(effectiveRole));
            userInfo.put("email", getSampleEmailByRole(effectiveRole));
            userInfo.put("phone", "0988776655");
            userInfo.put("status", "ACTIVE");
            userInfo.put("role", effectiveRole);
            userInfo.put("roles", List.of(effectiveRole));
            userInfo.put("warehouse", getDefaultWarehouseForRole(effectiveRole));
            userInfo.put("workLocation", getDefaultLocationForRole(effectiveRole));
        }

        response.put("user", userInfo);

        // 3. Danh sách menu lọc theo quyền (kết hợp tất cả vai trò người dùng được cấp)
        Set<String> effectiveRoles = new HashSet<>();
        if (!userRoles.isEmpty()) {
            effectiveRoles.addAll(userRoles);
        }
        if (effectiveRole != null && !effectiveRole.isBlank()) {
            effectiveRoles.add(effectiveRole.toUpperCase());
        }
        if (effectiveRoles.isEmpty()) {
            effectiveRoles.add("ROLE_ADMIN");
        }
        List<Map<String, String>> menus = buildAuthorizedMenus(effectiveRoles);
        response.put("menus", menus);

        return ResponseEntity.ok(response);
    }

    private String getSampleUsernameByRole(String role) {
        if (role == null) return "admin";
        switch (role.toUpperCase()) {
            case "ROLE_SALES_MANAGER": return "sales_manager";
            case "ROLE_SALES_REP": return "sales_rep";
            case "ROLE_WAREHOUSE": return "wh_staff";
            case "ROLE_WH_MANAGER": return "wh_manager";
            case "ROLE_ACCOUNTANT": return "accountant";
            case "ROLE_CUSTOMER": return "customer_agent";
            case "ROLE_ADMIN":
            default: return "admin";
        }
    }

    private String getSampleFullNameByRole(String role) {
        if (role == null) return "Quản Trị Viên Hệ Thống";
        switch (role.toUpperCase()) {
            case "ROLE_SALES_MANAGER": return "Trần Quản Lý Kinh Doanh";
            case "ROLE_SALES_REP": return "Lê Văn Bán Hàng";
            case "ROLE_WAREHOUSE": return "Nguyễn Văn Thủ Kho";
            case "ROLE_WH_MANAGER": return "Hoàng Quản Lý Kho";
            case "ROLE_ACCOUNTANT": return "Phạm Thị Kế Toán";
            case "ROLE_CUSTOMER": return "Đại Lý Minh Phát (B2B)";
            case "ROLE_ADMIN":
            default: return "Quản Trị Viên Hệ Thống";
        }
    }

    private String getSampleEmailByRole(String role) {
        if (role == null) return "okluon123pk@gmail.com";
        switch (role.toUpperCase()) {
            case "ROLE_SALES_MANAGER": return "manager@erp.com";
            case "ROLE_SALES_REP": return "salesrep@erp.com";
            case "ROLE_WAREHOUSE": return "warehouse@erp.com";
            case "ROLE_WH_MANAGER": return "whmanager@erp.com";
            case "ROLE_ACCOUNTANT": return "accountant@erp.com";
            case "ROLE_CUSTOMER": return "minhphat@daily.com";
            case "ROLE_ADMIN":
            default: return "okluon123pk@gmail.com";
        }
    }

    private String getDefaultWarehouseForRole(String role) {
        if (role == null) return "Chưa có";
        switch (role.toUpperCase()) {
            case "ROLE_WAREHOUSE": return "Kho Tổng Miền Bắc (WH-MB01)";
            case "ROLE_WH_MANAGER": return "Cụm Kho Tổng Phía Bắc (WH-MB01 & MB02)";
            case "ROLE_SALES_REP": return "Địa bàn: Quận 1 & TP. Thủ Đức, HCM";
            case "ROLE_SALES_MANAGER": return "Vùng phụ trách: Toàn miền Nam";
            case "ROLE_ACCOUNTANT": return "Phòng Kế toán - Trụ sở chính";
            case "ROLE_CUSTOMER": return "Điểm nhận hàng: Kho Cần Thơ";
            case "ROLE_ADMIN":
            default: return "Chưa có";
        }
    }

    private String getDefaultLocationForRole(String role) {
        if (role == null) return "Chưa có";
        switch (role.toUpperCase()) {
            case "ROLE_WAREHOUSE": return "Khu công nghiệp Tiên Sơn, Bắc Ninh";
            case "ROLE_WH_MANAGER": return "KCN Tiên Sơn & KCN Quang Minh";
            case "ROLE_SALES_REP": return "Văn phòng Đại diện Chi nhánh Miền Nam";
            case "ROLE_SALES_MANAGER": return "Văn phòng Đại diện TP. Hồ Chí Minh";
            case "ROLE_ACCOUNTANT": return "Văn phòng Kế toán Trung tâm";
            case "ROLE_CUSTOMER": return "Chi nhánh Phân phối Minh Phát - Cần Thơ";
            case "ROLE_ADMIN":
            default: return "Chưa có";
        }
    }

    private List<Map<String, String>> buildAuthorizedMenus(Set<String> roles) {
        List<Map<String, String>> menus = new ArrayList<>();
        menus.add(createMenuItem("Bàn làm việc", "/dashboard", "LayoutDashboard", "Tổng quan hoạt động"));

        // Sprint 1: Nếu người dùng có quyền Quản trị viên (ADMIN) thì hiển thị Quản lý tài khoản
        if (roles != null && roles.contains("ROLE_ADMIN")) {
            menus.add(createMenuItem("Quản lý tài khoản", "/users", "Users", "Quản lý nhân sự & tài khoản"));
        }

        menus.add(createMenuItem("Hồ sơ cá nhân", "/profile", "User", "Thông tin nhân sự"));
        return menus;
    }

    private Map<String, String> createMenuItem(String title, String path, String icon, String description) {
        Map<String, String> item = new HashMap<>();
        item.put("title", title);
        item.put("path", path);
        item.put("icon", icon);
        item.put("description", description);
        return item;
    }
}
