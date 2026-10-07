package com.erp.backend.service;

import com.erp.backend.dto.user.CreateUserRequest;
import com.erp.backend.dto.user.CreateUserResponse;
import com.erp.backend.dto.user.UpdateUserRequest;
import com.erp.backend.dto.user.UserAssignmentRequest;
import com.erp.backend.dto.user.UserResponse;
import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.RegionRepository;
import com.erp.backend.repository.RoleRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserManagementServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private WarehouseRepository warehouseRepository;
    @Mock private RegionRepository regionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private TempPasswordGenerator tempPasswordGenerator;
    @Mock private MailService mailService;

    @InjectMocks private UserManagementService service;

    private final Map<RoleName, Role> roles = new EnumMap<>(RoleName.class);

    @BeforeEach
    void setUp() {
        long id = 1;
        for (RoleName rn : RoleName.values()) {
            roles.put(rn, Role.builder().id(id++).name(rn).build());
        }
        lenient().when(roleRepository.findByNameIn(anyCollection())).thenAnswer(inv -> {
            Collection<RoleName> names = inv.getArgument(0);
            return names.stream().map(roles::get).toList();
        });
        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateUserRequest newRequest(String username, RoleName... roleNames) {
        CreateUserRequest req = new CreateUserRequest();
        req.setUsername(username);
        req.setFullName("Nguyễn Văn A");
        req.setEmail(username + "@erp.com");
        req.setPhone("0912345678");
        req.setRoles(new HashSet<>(Arrays.asList(roleNames)));
        return req;
    }

    private User existingUser(Long id, RoleName... roleNames) {
        Set<Role> set = new HashSet<>();
        for (RoleName rn : roleNames) set.add(roles.get(rn));
        return User.builder().id(id).username("user" + id).fullName("User " + id)
                .email("user" + id + "@erp.com").password("x").roles(set).build();
    }

    // ========================== S1-08 ==========================

    @Test
    @DisplayName("S1-08: Tạo tài khoản thành công -> băm mật khẩu tạm, bắt đổi mật khẩu, gửi email")
    void create_success() {
        when(tempPasswordGenerator.generate()).thenReturn("Temp1234ab");
        when(passwordEncoder.encode("Temp1234ab")).thenReturn("HASHED");
        when(mailService.isConfigured()).thenReturn(true);

        CreateUserResponse res = service.create(newRequest("Sales01", RoleName.ROLE_SALES_REP));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getUsername()).isEqualTo("sales01"); // chuẩn hoá chữ thường
        assertThat(saved.getPassword()).isEqualTo("HASHED"); // không lưu mật khẩu gốc
        assertThat(saved.isMustChangePassword()).isTrue();
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(res.activationEmailSent()).isTrue();
        assertThat(res.user().getRoles()).containsExactly("ROLE_SALES_REP");
        // Email được giao cho luồng chạy ngầm với đúng người nhận và mật khẩu tạm
        verify(mailService).sendAccountCreatedEmail("sales01@erp.com", "Nguyễn Văn A", "sales01", "Temp1234ab");
    }

    @Test
    @DisplayName("S1-08: Tên tài khoản trùng -> từ chối 409 kèm thông báo cụ thể")
    void create_duplicateUsername_rejected() {
        when(userRepository.existsByUsernameIgnoreCase("sales01")).thenReturn(true);

        assertThatThrownBy(() -> service.create(newRequest("sales01", RoleName.ROLE_SALES_REP)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sales01")
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(be.getField()).isEqualTo("username");
                });
        verify(userRepository, never()).save(any());
        verify(mailService, never()).sendAccountCreatedEmail(any(), any(), any(), any());
    }

    @Test
    @DisplayName("S1-08: Email trùng -> từ chối với field = email")
    void create_duplicateEmail_rejected() {
        when(userRepository.existsByEmailIgnoreCase("sales01@erp.com")).thenReturn(true);

        assertThatThrownBy(() -> service.create(newRequest("sales01", RoleName.ROLE_SALES_REP)))
                .isInstanceOf(BusinessException.class)
                .extracting("field").isEqualTo("email");
    }

    @Test
    @DisplayName("S1-08: Chưa cấu hình SMTP -> vẫn tạo được tài khoản, báo activationEmailSent = false")
    void create_mailNotSent_stillCreated() {
        when(tempPasswordGenerator.generate()).thenReturn("Temp1234ab");
        when(mailService.isConfigured()).thenReturn(false);

        CreateUserResponse res = service.create(newRequest("sales02", RoleName.ROLE_SALES_REP));

        verify(userRepository).save(any());
        assertThat(res.activationEmailSent()).isFalse();
    }

    @Test
    @DisplayName("S1-08: Sửa email trùng với người khác -> từ chối")
    void update_duplicateEmail_rejected() {
        when(userRepository.findById(5L)).thenReturn(Optional.of(existingUser(5L, RoleName.ROLE_SALES_REP)));
        when(userRepository.existsByEmailIgnoreCaseAndIdNot("taken@erp.com", 5L)).thenReturn(true);

        UpdateUserRequest req = new UpdateUserRequest();
        req.setFullName("Tên mới");
        req.setEmail("Taken@erp.com");

        assertThatThrownBy(() -> service.update(5L, req))
                .isInstanceOf(BusinessException.class)
                .extracting("field").isEqualTo("email");
    }

    @Test
    @DisplayName("S1-08: Kích thước trang mặc định là 20 và không vượt quá 100")
    void pageSize_defaults() {
        assertThat(UserManagementService.DEFAULT_PAGE_SIZE).isEqualTo(20);
        assertThat(UserManagementService.MAX_PAGE_SIZE).isEqualTo(100);
    }

    // ========================== S1-09 ==========================

    @Test
    @DisplayName("S1-09: Một người dùng giữ được nhiều vai trò cùng lúc")
    void assign_multipleRoles_ok() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser(7L, RoleName.ROLE_SALES_REP)));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_SALES_REP, RoleName.ROLE_ACCOUNTANT));

        UserResponse res = service.updateAssignments(7L, req, 1L);

        assertThat(res.getRoles()).containsExactlyInAnyOrder("ROLE_SALES_REP", "ROLE_ACCOUNTANT");
    }

    @Test
    @DisplayName("S1-09: Vai trò kho mà không gắn kho nào -> từ chối")
    void assign_warehouseRoleWithoutWarehouse_rejected() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser(7L, RoleName.ROLE_SALES_REP)));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_WAREHOUSE));

        assertThatThrownBy(() -> service.updateAssignments(7L, req, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WAREHOUSE_REQUIRED");
    }

    @Test
    @DisplayName("S1-09: Vai trò kho gắn đúng 1 kho đang hoạt động -> thành công")
    void assign_warehouseRoleWithWarehouse_ok() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser(7L)));
        Warehouse kho = Warehouse.builder().id(10L).code("KHO-HN").name("Kho HN").status("ACTIVE").build();
        when(warehouseRepository.findAllById(Set.of(10L))).thenReturn(List.of(kho));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_WH_MANAGER));
        req.setWarehouseIds(Set.of(10L));

        UserResponse res = service.updateAssignments(7L, req, 1L);

        assertThat(res.getWarehouses()).extracting("code").containsExactly("KHO-HN");
    }

    @Test
    @DisplayName("S1-09: Gắn kho không tồn tại -> từ chối")
    void assign_unknownWarehouse_rejected() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser(7L)));
        when(warehouseRepository.findAllById(Set.of(99L))).thenReturn(List.of());

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_WAREHOUSE));
        req.setWarehouseIds(Set.of(99L));

        assertThatThrownBy(() -> service.updateAssignments(7L, req, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("WAREHOUSE_INVALID");
    }

    @Test
    @DisplayName("S1-09: Admin KHÔNG tự thu hồi được vai trò ADMIN của chính mình")
    void assign_cannotRevokeOwnAdmin() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(existingUser(1L, RoleName.ROLE_ADMIN)));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_SALES_MANAGER));

        assertThatThrownBy(() -> service.updateAssignments(1L, req, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("CANNOT_REVOKE_OWN_ADMIN");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("S1-09: Admin thu hồi được vai trò ADMIN của NGƯỜI KHÁC")
    void assign_canRevokeOtherAdmin() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(existingUser(2L, RoleName.ROLE_ADMIN)));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of(RoleName.ROLE_SALES_MANAGER));

        UserResponse res = service.updateAssignments(2L, req, 1L);

        assertThat(res.getRoles()).containsExactly("ROLE_SALES_MANAGER");
    }

    @Test
    @DisplayName("S1-09: Không chọn vai trò nào -> từ chối")
    void assign_emptyRoles_rejected() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(existingUser(7L, RoleName.ROLE_SALES_REP)));

        UserAssignmentRequest req = new UserAssignmentRequest();
        req.setRoles(Set.of());

        assertThatThrownBy(() -> service.updateAssignments(7L, req, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("ROLE_REQUIRED");
    }

    @Test
    @DisplayName("Tài khoản không tồn tại -> 404")
    void getById_notFound() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getById(404L))
                .isInstanceOf(BusinessException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
    }
}
