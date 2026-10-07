package com.erp.backend.config;

import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.repository.RoleRepository;
import com.erp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        // 1. Khởi tạo 7 Vai trò nghiệp vụ nếu trong DB chưa có
        for (RoleName roleName : RoleName.values()) {
            if (roleRepository.findByName(roleName).isEmpty()) {
                roleRepository.save(Role.builder()
                        .name(roleName)
                        .description("Vai trò " + roleName.name())
                        .build());
            }
        }

        // 2. Khởi tạo & đảm bảo mật khẩu hoạt động cho toàn bộ 7 tài khoản mẫu chuẩn Sprint 1
        seedUser("admin", "admin123", "Quản Trị Viên Hệ Thống", "okluon123pk@gmail.com", "0987654321", RoleName.ROLE_ADMIN);
        seedUser("sales_manager", "manager123", "Trần Quản Lý Kinh Doanh", "manager@erp.com", "0912345678", RoleName.ROLE_SALES_MANAGER);
        seedUser("sales_rep", "sales123", "Lê Văn Bán Hàng", "salesrep@erp.com", "0923456789", RoleName.ROLE_SALES_REP);
        seedUser("tran_minh", "sales123", "Trần Minh", "tranminh@erp.com", "0912345679", RoleName.ROLE_SALES_REP);
        seedUser("wh_staff", "wh123", "Nguyễn Văn Thủ Kho", "warehouse@erp.com", "0934567890", RoleName.ROLE_WAREHOUSE);
        seedUser("wh_manager", "wh123", "Hoàng Quản Lý Kho", "whmanager@erp.com", "0945678901", RoleName.ROLE_WH_MANAGER);
        seedUser("accountant", "acc123", "Phạm Thị Kế Toán", "accountant@erp.com", "0956789012", RoleName.ROLE_ACCOUNTANT);
        seedUser("customer_agent", "cust123", "Đại Lý Minh Phát (B2B)", "minhphat@daily.com", "0967890123", RoleName.ROLE_CUSTOMER);
    }

    private void seedUser(String username, String rawPassword, String fullName, String email, String phone, RoleName roleName) {
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new RuntimeException("Lỗi: Không tìm thấy Role " + roleName));
        Set<Role> roles = new HashSet<>();
        roles.add(role);

        userRepository.findByUsername(username).ifPresentOrElse(existingUser -> {
            // Cập nhật lại mật khẩu băm và mở khoá nếu trước đó bị lỗi / sai pass
            existingUser.setPassword(passwordEncoder.encode(rawPassword));
            existingUser.setStatus("ACTIVE");
            existingUser.setFailedLoginAttempts(0);
            existingUser.setLockUntil(null);
            existingUser.setMustChangePassword(false);
            existingUser.setFullName(fullName);
            existingUser.setEmail(email);
            existingUser.setRoles(roles);
            userRepository.save(existingUser);
        }, () -> {
            User user = User.builder()
                    .username(username)
                    .password(passwordEncoder.encode(rawPassword))
                    .fullName(fullName)
                    .email(email)
                    .phone(phone)
                    .status("ACTIVE")
                    .failedLoginAttempts(0)
                    .mustChangePassword(false)
                    .roles(roles)
                    .build();

            userRepository.save(user);
            System.out.println(">>> ĐÃ KHỞI TẠO TÀI KHOẢN MẪU: " + username + " / " + rawPassword + " (" + roleName + ") <<<");
        });
    }
}
