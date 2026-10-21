package com.erp.backend.config;

import com.erp.backend.entity.Customer;
import com.erp.backend.entity.Region;
import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.SalesOrder;
import com.erp.backend.entity.SalesTarget;
import com.erp.backend.entity.User;
import com.erp.backend.repository.CustomerRepository;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.RoleRepository;
import com.erp.backend.repository.SalesOrderRepository;
import com.erp.backend.repository.SalesTargetRepository;
import com.erp.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final CustomerRepository customerRepository;
    private final SalesTargetRepository targetRepository;
    private final RegionRepository regionRepository;
    private final SalesOrderRepository salesOrderRepository;

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

        // 2. Khởi tạo & đảm bảo mật khẩu hoạt động cho toàn bộ tài khoản mẫu
        seedUser("admin", "admin123", "Quản Trị Viên Hệ Thống", "okluon123pk@gmail.com", "0987654321", RoleName.ROLE_ADMIN);
        seedUser("sales_manager", "manager123", "Trần Quản Lý Kinh Doanh", "manager@erp.com", "0912345678", RoleName.ROLE_SALES_MANAGER);
        seedUser("sales_rep", "sales123", "Lê Văn Bán Hàng", "salesrep@erp.com", "0923456789", RoleName.ROLE_SALES_REP);
        seedUser("tran_minh", "sales123", "Trần Minh", "tranminh@erp.com", "0912345679", RoleName.ROLE_SALES_REP);
        seedUser("phong2006", "sales123", "lehongphong", "lephong21082006@gmail.com", "0926143314", RoleName.ROLE_SALES_REP);
        seedUser("sales_sg01", "sales123", "Lê Thị Mai", "mai.lt@erp.com", "0903123456", RoleName.ROLE_SALES_REP);
        seedUser("wh_staff", "wh123", "Nguyễn Văn Thủ Kho", "warehouse@erp.com", "0934567890", RoleName.ROLE_WAREHOUSE);
        seedUser("wh_manager", "wh123", "Hoàng Quản Lý Kho", "whmanager@erp.com", "0945678901", RoleName.ROLE_WH_MANAGER);
        seedUser("accountant", "acc123", "Phạm Thị Kế Toán", "accountant@erp.com", "0956789012", RoleName.ROLE_ACCOUNTANT);
        seedUser("customer_agent", "cust123", "Đại Lý Minh Phát (B2B)", "minhphat@daily.com", "0967890123", RoleName.ROLE_CUSTOMER);

        // 3. S4-10: Gắn tài khoản đại lý mẫu với một hồ sơ đại lý để test cổng đại lý (chỉ khi chưa gắn)
        linkDemoPortalAccount("customer_agent");

        // 4. S8-04: Khởi tạo dữ liệu chỉ tiêu và đơn hàng thực tế (kỳ hiện tại 2026-10 và kỳ quá hạn 2026-09)
        seedSalesKpiAndDemoData();
    }

    /**
     * Ưu tiên đại lý mã DL-HN-001, rồi đại lý tên có "Minh Phát", rồi đại lý đang hoạt động có id nhỏ nhất;
     * bỏ qua nếu tài khoản đã gắn, hệ thống chưa có đại lý hoặc đại lý đã có tài khoản khác.
     */
    private void linkDemoPortalAccount(String username) {
        userRepository.findByUsername(username).ifPresent(user -> {
            if (customerRepository.findByPortalUser_Id(user.getId()).isPresent()) {
                return;
            }
            List<Customer> customers = customerRepository.findAll(Sort.by("id"));
            customers.stream().filter(c -> c.getUser() != null && user.getId().equals(c.getUser().getId()))
                    .findFirst()
                    .or(() -> customers.stream().filter(c -> "DL-HN-001".equalsIgnoreCase(c.getCode())).findFirst())
                    .or(() -> customers.stream()
                            .filter(c -> c.getName() != null && c.getName().toLowerCase().contains("minh phát")).findFirst())
                    .or(() -> customers.stream().filter(c -> "ACTIVE".equalsIgnoreCase(c.getStatus())).findFirst())
                    .filter(c -> c.getPortalUser() == null)
                    .filter(c -> c.getUser() == null || user.getId().equals(c.getUser().getId()))
                    .ifPresent(c -> {
                        c.setPortalUser(user);
                        c.setUser(user);
                        customerRepository.save(c);
                    });
        });
    }

    private void seedUser(String username, String rawPassword, String fullName, String email, String phone, RoleName roleName) {
        Role role = roleRepository.findByName(roleName)
                .orElseThrow(() -> new RuntimeException("Lỗi: Không tìm thấy Role " + roleName));

        userRepository.findByUsername(username).ifPresentOrElse(existingUser -> {
            existingUser.setPassword(passwordEncoder.encode(rawPassword));
            existingUser.setStatus("ACTIVE");
            existingUser.setFailedLoginAttempts(0);
            existingUser.setLockUntil(null);
            existingUser.setMustChangePassword(false);
            if (fullName != null && !fullName.isBlank()) {
                existingUser.setFullName(fullName);
            }
            if (email != null && !email.isBlank()) {
                existingUser.setEmail(email);
            }
            if (existingUser.getRoles() == null) {
                existingUser.setRoles(new HashSet<>());
            }
            existingUser.getRoles().add(role);
            userRepository.save(existingUser);
        }, () -> {
            Set<Role> roles = new HashSet<>();
            roles.add(role);
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

        // S5-01: Tự động liên kết tài khoản đại lý với hồ sơ đại lý tương ứng (nếu có)
        if (roleName == RoleName.ROLE_CUSTOMER) {
            userRepository.findByUsername(username).ifPresent(u -> {
                customerRepository.findByEmailIgnoreCase(u.getEmail())
                        .or(() -> customerRepository.findByCodeIgnoreCase(u.getUsername()))
                        .ifPresent(c -> {
                            if (c.getUser() == null || !c.getUser().getId().equals(u.getId())) {
                                c.setUser(u);
                                customerRepository.save(c);
                            }
                        });
            });
        }
    }

    /**
     * S8-04: Khởi tạo dữ liệu mẫu chỉ tiêu kinh doanh (KPI) cho cả kỳ hiện tại (2026-10) và quá kỳ (2026-09),
     * gán đại lý cho NVKD và phát sinh đơn hàng có doanh số thực đạt để trang KPI hiển thị đầy đủ, chính xác.
     */
    private void seedSalesKpiAndDemoData() {
        User repPhong = userRepository.findByUsername("phong2006").orElse(null);
        User repMai = userRepository.findByUsername("sales_sg01").orElse(null);

        // 1. Gán đại lý cho NVKD phụ trách chính
        if (repPhong != null) {
            customerRepository.findAll().stream()
                    .filter(c -> (c.getCode() != null && c.getCode().equalsIgnoreCase("DL-CC-123"))
                            || (c.getName() != null && c.getName().toLowerCase().contains("le hong phong")))
                    .findFirst()
                    .ifPresent(c -> {
                        c.setSalesRep(repPhong);
                        customerRepository.save(c);
                    });
            customerRepository.findByCodeIgnoreCase("VL-123").ifPresent(c -> {
                if (c.getSalesRep() == null) {
                    c.setSalesRep(repPhong);
                    customerRepository.save(c);
                }
            });
        }

        if (repMai != null) {
            customerRepository.findAll().stream()
                    .filter(c -> (c.getCode() != null && c.getCode().equalsIgnoreCase("SEX-123"))
                            || (c.getName() != null && c.getName().toLowerCase().contains("pikachu")))
                    .findFirst()
                    .ifPresent(c -> {
                        c.setSalesRep(repMai);
                        customerRepository.save(c);
                    });
        }

        Region mb = regionRepository.findByCodeIgnoreCase("MB")
                .or(() -> regionRepository.findByNameIgnoreCase("Miền Bắc"))
                .orElse(null);
        Region mn = regionRepository.findByCodeIgnoreCase("MN")
                .or(() -> regionRepository.findByNameIgnoreCase("Miền Nam"))
                .orElse(null);

        // 2. Chỉ tiêu KỲ QUÁ HẠN (2026-09) - trạng thái khoá
        LocalDate pastPeriod = LocalDate.of(2026, 9, 1);
        if (repPhong != null) {
            String key = SalesTarget.keyOf(SalesTarget.TYPE_REP, repPhong.getId());
            if (!targetRepository.existsByPeriodMonthAndTargetKey(pastPeriod, key)) {
                targetRepository.save(SalesTarget.builder()
                        .periodMonth(pastPeriod)
                        .targetType(SalesTarget.TYPE_REP)
                        .targetKey(key)
                        .salesRep(repPhong)
                        .targetAmount(new BigDecimal("400000000.00"))
                        .note("Chỉ tiêu tháng 09/2026 (Kỳ quá hạn đã khoá)")
                        .createdByUsername("sales_manager")
                        .updatedByUsername("sales_manager")
                        .build());
            }
        }
        if (repMai != null) {
            String key = SalesTarget.keyOf(SalesTarget.TYPE_REP, repMai.getId());
            if (!targetRepository.existsByPeriodMonthAndTargetKey(pastPeriod, key)) {
                targetRepository.save(SalesTarget.builder()
                        .periodMonth(pastPeriod)
                        .targetType(SalesTarget.TYPE_REP)
                        .targetKey(key)
                        .salesRep(repMai)
                        .targetAmount(new BigDecimal("350000000.00"))
                        .note("Chỉ tiêu tháng 09/2026 (Kỳ quá hạn đã khoá)")
                        .createdByUsername("sales_manager")
                        .updatedByUsername("sales_manager")
                        .build());
            }
        }
        if (mb != null) {
            String key = SalesTarget.keyOf(SalesTarget.TYPE_REGION, mb.getId());
            if (!targetRepository.existsByPeriodMonthAndTargetKey(pastPeriod, key)) {
                targetRepository.save(SalesTarget.builder()
                        .periodMonth(pastPeriod)
                        .targetType(SalesTarget.TYPE_REGION)
                        .targetKey(key)
                        .region(mb)
                        .targetAmount(new BigDecimal("800000000.00"))
                        .note("Chỉ tiêu khu vực Miền Bắc tháng 09/2026 (Kỳ quá hạn đã khoá)")
                        .createdByUsername("sales_manager")
                        .updatedByUsername("sales_manager")
                        .build());
            }
        }
        if (mn != null) {
            String key = SalesTarget.keyOf(SalesTarget.TYPE_REGION, mn.getId());
            if (!targetRepository.existsByPeriodMonthAndTargetKey(pastPeriod, key)) {
                targetRepository.save(SalesTarget.builder()
                        .periodMonth(pastPeriod)
                        .targetType(SalesTarget.TYPE_REGION)
                        .targetKey(key)
                        .region(mn)
                        .targetAmount(new BigDecimal("700000000.00"))
                        .note("Chỉ tiêu khu vực Miền Nam tháng 09/2026 (Kỳ quá hạn đã khoá)")
                        .createdByUsername("sales_manager")
                        .updatedByUsername("sales_manager")
                        .build());
            }
        }

        // 3. Đơn hàng phát sinh doanh số thực đạt để đồng bộ KPI
        Customer cPhong = customerRepository.findAll().stream()
                .filter(c -> (c.getCode() != null && c.getCode().equalsIgnoreCase("DL-CC-123"))
                        || (c.getName() != null && c.getName().toLowerCase().contains("le hong phong")))
                .findFirst().orElse(null);
        Customer cMai = customerRepository.findAll().stream()
                .filter(c -> (c.getCode() != null && c.getCode().equalsIgnoreCase("SEX-123"))
                        || (c.getName() != null && c.getName().toLowerCase().contains("pikachu")))
                .findFirst().orElse(null);

        // Đơn tháng 10/2026 cho phong2006 (150.000.000 đ)
        if (cPhong != null && !salesOrderRepository.existsByCode("DH2610-P01")) {
            salesOrderRepository.save(SalesOrder.builder()
                    .code("DH2610-P01")
                    .customer(cPhong)
                    .status(SalesOrder.STATUS_APPROVED)
                    .subtotal(new BigDecimal("150000000.00"))
                    .discountTotal(BigDecimal.ZERO)
                    .totalAmount(new BigDecimal("150000000.00"))
                    .approvedAt(LocalDateTime.of(2026, 10, 15, 10, 0, 0))
                    .createdAt(LocalDateTime.of(2026, 10, 15, 9, 30, 0))
                    .createdByUsername("phong2006")
                    .createdById(repPhong != null ? repPhong.getId() : null)
                    .note("Đơn hàng đại lý tháng 10 - lehongphong")
                    .build());
        }

        // Đơn tháng 10/2026 cho sales_sg01 (215.000.000 đ)
        if (cMai != null && !salesOrderRepository.existsByCode("DH2610-SG01")) {
            salesOrderRepository.save(SalesOrder.builder()
                    .code("DH2610-SG01")
                    .customer(cMai)
                    .status(SalesOrder.STATUS_APPROVED)
                    .subtotal(new BigDecimal("215000000.00"))
                    .discountTotal(BigDecimal.ZERO)
                    .totalAmount(new BigDecimal("215000000.00"))
                    .approvedAt(LocalDateTime.of(2026, 10, 16, 14, 0, 0))
                    .createdAt(LocalDateTime.of(2026, 10, 16, 13, 30, 0))
                    .createdByUsername("sales_sg01")
                    .createdById(repMai != null ? repMai.getId() : null)
                    .note("Đơn hàng đại lý tháng 10 - Lê Thị Mai")
                    .build());
        }

        // Đơn tháng 9/2026 (Quá kỳ) cho phong2006 (380.000.000 đ)
        if (cPhong != null && !salesOrderRepository.existsByCode("DH2609-P01")) {
            salesOrderRepository.save(SalesOrder.builder()
                    .code("DH2609-P01")
                    .customer(cPhong)
                    .status(SalesOrder.STATUS_DELIVERED)
                    .subtotal(new BigDecimal("380000000.00"))
                    .discountTotal(BigDecimal.ZERO)
                    .totalAmount(new BigDecimal("380000000.00"))
                    .approvedAt(LocalDateTime.of(2026, 9, 20, 11, 0, 0))
                    .createdAt(LocalDateTime.of(2026, 9, 20, 10, 0, 0))
                    .createdByUsername("phong2006")
                    .createdById(repPhong != null ? repPhong.getId() : null)
                    .note("Đơn hàng tháng 9/2026 - lehongphong")
                    .build());
        }

        // Đơn tháng 9/2026 (Quá kỳ) cho sales_sg01 (360.000.000 đ)
        if (cMai != null && !salesOrderRepository.existsByCode("DH2609-SG01")) {
            salesOrderRepository.save(SalesOrder.builder()
                    .code("DH2609-SG01")
                    .customer(cMai)
                    .status(SalesOrder.STATUS_DELIVERED)
                    .subtotal(new BigDecimal("360000000.00"))
                    .discountTotal(BigDecimal.ZERO)
                    .totalAmount(new BigDecimal("360000000.00"))
                    .approvedAt(LocalDateTime.of(2026, 9, 25, 16, 0, 0))
                    .createdAt(LocalDateTime.of(2026, 9, 25, 15, 0, 0))
                    .createdByUsername("sales_sg01")
                    .createdById(repMai != null ? repMai.getId() : null)
                    .note("Đơn hàng tháng 9/2026 - Lê Thị Mai")
                    .build());
        }
    }
}
