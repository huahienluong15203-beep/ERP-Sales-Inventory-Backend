package com.erp.backend.service;

import com.erp.backend.dto.user.AvatarUploadResponse;
import com.erp.backend.dto.user.PersonalProfileResponse;
import com.erp.backend.dto.user.UpdatePersonalProfileRequest;
import com.erp.backend.entity.Role;
import com.erp.backend.entity.RoleName;
import com.erp.backend.entity.User;
import com.erp.backend.entity.Warehouse;
import com.erp.backend.exception.BusinessException;
import com.erp.backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test UserProfileService - S2-02, S2-03 Hồ sơ cá nhân & Ảnh đại diện")
class UserProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AvatarStorageService avatarStorageService;

    @InjectMocks
    private UserProfileService userProfileService;

    private User sampleUser;

    @BeforeEach
    void setUp() {
        Role role = Role.builder().id(1L).name(RoleName.ROLE_SALES_REP).build();
        Warehouse wh = Warehouse.builder().id(10L).code("WH-MB01").name("Kho Hà Nội").build();

        sampleUser = User.builder()
                .id(100L)
                .username("sales_rep_01")
                .fullName("Nguyễn Văn A")
                .email("sales@erp.com")
                .phone("0912345678")
                .status("ACTIVE")
                .roles(Set.of(role))
                .warehouses(Set.of(wh))
                .build();

        lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("S2-02: Lấy thông tin hồ sơ cá nhân thành công")
    void getProfile_Success() {
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));

        PersonalProfileResponse res = userProfileService.getProfile(100L);

        assertThat(res).isNotNull();
        assertThat(res.getId()).isEqualTo(100L);
        assertThat(res.getUsername()).isEqualTo("sales_rep_01");
        assertThat(res.getFullName()).isEqualTo("Nguyễn Văn A");
        assertThat(res.getEmail()).isEqualTo("sales@erp.com");
        assertThat(res.getPhone()).isEqualTo("0912345678");
        assertThat(res.getRoles()).contains("ROLE_SALES_REP");
    }

    @Test
    @DisplayName("S2-02: Cập nhật họ tên và số điện thoại Việt Nam thành công")
    void updateProfile_Success() {
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.existsByPhoneAndIdNot("0987654321", 100L)).thenReturn(false);

        UpdatePersonalProfileRequest req = UpdatePersonalProfileRequest.builder()
                .fullName("Nguyễn Văn An Cập Nhật")
                .phone("0987654321")
                .build();

        PersonalProfileResponse res = userProfileService.updateProfile(100L, req);

        assertThat(res.getFullName()).isEqualTo("Nguyễn Văn An Cập Nhật");
        assertThat(res.getPhone()).isEqualTo("0987654321");
        // Kiểm tra tính bất biến: username, email, vai trò không bị đổi
        assertThat(res.getUsername()).isEqualTo("sales_rep_01");
        assertThat(res.getEmail()).isEqualTo("sales@erp.com");
        verify(userRepository).save(sampleUser);
    }

    @Test
    @DisplayName("S2-02: Chuẩn hoá số điện thoại định dạng +84 thành đầu số 0")
    void updateProfile_NormalizesPlus84Phone() {
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.existsByPhoneAndIdNot("0987654321", 100L)).thenReturn(false);

        UpdatePersonalProfileRequest req = UpdatePersonalProfileRequest.builder()
                .fullName("Nguyễn Văn An")
                .phone("+84987654321")
                .build();

        PersonalProfileResponse res = userProfileService.updateProfile(100L, req);

        assertThat(res.getPhone()).isEqualTo("0987654321");
    }

    @Test
    @DisplayName("S2-02: Chặn trùng số điện thoại với tài khoản khác")
    void updateProfile_ThrowsWhenPhoneAlreadyExists() {
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));
        when(userRepository.existsByPhoneAndIdNot("0988888888", 100L)).thenReturn(true);

        UpdatePersonalProfileRequest req = UpdatePersonalProfileRequest.builder()
                .fullName("Nguyễn Văn A")
                .phone("0988888888")
                .build();

        assertThatThrownBy(() -> userProfileService.updateProfile(100L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Số điện thoại '0988888888' đã được sử dụng");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("S2-03: Tải lên avatar thành công và xoá avatar cũ nếu đã tồn tại")
    void uploadAvatar_Success() {
        sampleUser.setAvatarUrl("/uploads/avatars/old_avatar.png");
        sampleUser.setAvatarThumbnailUrl("/uploads/avatars/old_avatar_thumb.png");
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));

        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", new byte[]{1, 2, 3});
        when(avatarStorageService.processAndStoreAvatar(100L, file, null, null, null, null))
                .thenReturn(new AvatarStorageService.AvatarResult("/uploads/avatars/new.png", "/uploads/avatars/new_thumb.png"));

        AvatarUploadResponse res = userProfileService.uploadAvatar(100L, file, null, null, null, null);

        assertThat(res).isNotNull();
        assertThat(res.getAvatarUrl()).isEqualTo("/uploads/avatars/new.png");
        assertThat(res.getAvatarThumbnailUrl()).isEqualTo("/uploads/avatars/new_thumb.png");
        assertThat(res.getProfile().getAvatarUrl()).isEqualTo("/uploads/avatars/new.png");

        // Xác nhận đã xoá avatar cũ
        verify(avatarStorageService).deleteAvatarFiles("/uploads/avatars/old_avatar.png", "/uploads/avatars/old_avatar_thumb.png");
        verify(userRepository).save(sampleUser);
    }

    @Test
    @DisplayName("S2-03: Xoá avatar hiện tại trở về mặc định")
    void removeAvatar_Success() {
        sampleUser.setAvatarUrl("/uploads/avatars/my_avatar.png");
        sampleUser.setAvatarThumbnailUrl("/uploads/avatars/my_avatar_thumb.png");
        when(userRepository.findById(100L)).thenReturn(Optional.of(sampleUser));

        PersonalProfileResponse res = userProfileService.removeAvatar(100L);

        assertThat(res).isNotNull();
        assertThat(res.getAvatarUrl()).isNull();
        assertThat(res.getAvatarThumbnailUrl()).isNull();

        verify(avatarStorageService).deleteAvatarFiles("/uploads/avatars/my_avatar.png", "/uploads/avatars/my_avatar_thumb.png");
        verify(userRepository).save(sampleUser);
    }
}
