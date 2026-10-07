package com.erp.backend.controller;

import com.erp.backend.dto.user.AvatarUploadResponse;
import com.erp.backend.dto.user.PersonalProfileResponse;
import com.erp.backend.dto.user.UpdatePersonalProfileRequest;
import com.erp.backend.security.UserDetailsImpl;
import com.erp.backend.service.UserProfileService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("Unit test PersonalProfileController - S2-02, S2-03 Hồ sơ cá nhân & Ảnh đại diện")
class PersonalProfileControllerTest {

    @Mock
    private UserProfileService userProfileService;

    @InjectMocks
    private PersonalProfileController personalProfileController;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private UserDetailsImpl mockUserDetails;
    private boolean injectUser = true;

    @BeforeEach
    void setUp() {
        mockUserDetails = new UserDetailsImpl(
                10L,
                "nguyenvana",
                "Nguyễn Văn A",
                "vana@erp.com",
                "encodedPassword",
                true,
                List.of(new SimpleGrantedAuthority("ROLE_SALES_REP"))
        );
        injectUser = true;

        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                return injectUser ? mockUserDetails : null;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(personalProfileController)
                .setCustomArgumentResolvers(authPrincipalResolver)
                .build();
    }

    @Test
    @DisplayName("S2-02: Chưa đăng nhập xem hồ sơ cá nhân -> 401 Unauthorized")
    void getProfile_Unauthenticated_Returns401() throws Exception {
        injectUser = false;
        mockMvc.perform(get("/api/v1/profile"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("S2-02: Đã đăng nhập xem hồ sơ cá nhân -> 200 OK")
    void getProfile_Authenticated_Returns200() throws Exception {
        PersonalProfileResponse response = PersonalProfileResponse.builder()
                .id(10L)
                .username("nguyenvana")
                .fullName("Nguyễn Văn A")
                .email("vana@erp.com")
                .phone("0912345678")
                .avatarUrl("/uploads/avatars/avatar_10.png")
                .avatarThumbnailUrl("/uploads/avatars/avatar_10_thumb.png")
                .status("ACTIVE")
                .roles(List.of("ROLE_SALES_REP"))
                .build();

        when(userProfileService.getProfile(10L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.username").value("nguyenvana"))
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn A"))
                .andExpect(jsonPath("$.phone").value("0912345678"))
                .andExpect(jsonPath("$.avatarUrl").value("/uploads/avatars/avatar_10.png"))
                .andExpect(jsonPath("$.avatarThumbnailUrl").value("/uploads/avatars/avatar_10_thumb.png"));
    }

    @Test
    @DisplayName("S2-02: Cập nhật họ tên và số điện thoại thành công -> 200 OK")
    void updateProfile_Authenticated_Returns200() throws Exception {
        UpdatePersonalProfileRequest request = UpdatePersonalProfileRequest.builder()
                .fullName("Nguyễn Văn A Mới")
                .phone("0987654321")
                .build();

        PersonalProfileResponse response = PersonalProfileResponse.builder()
                .id(10L)
                .fullName("Nguyễn Văn A Mới")
                .phone("0987654321")
                .build();

        when(userProfileService.updateProfile(eq(10L), any(UpdatePersonalProfileRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/v1/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Nguyễn Văn A Mới"))
                .andExpect(jsonPath("$.phone").value("0987654321"));
    }

    @Test
    @DisplayName("S2-03: Tải lên ảnh đại diện thành công -> 200 OK")
    void uploadAvatar_Authenticated_Returns200() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "avatar.jpg", "image/jpeg", new byte[]{1, 2, 3});

        AvatarUploadResponse res = AvatarUploadResponse.builder()
                .message("Tải ảnh đại diện thành công")
                .avatarUrl("/uploads/avatars/avatar_user_10_12345.jpg")
                .avatarThumbnailUrl("/uploads/avatars/avatar_user_10_12345_thumb.jpg")
                .build();

        when(userProfileService.uploadAvatar(eq(10L), any(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(res);

        mockMvc.perform(multipart("/api/v1/profile/avatar")
                        .file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Tải ảnh đại diện thành công"))
                .andExpect(jsonPath("$.avatarUrl").value("/uploads/avatars/avatar_user_10_12345.jpg"))
                .andExpect(jsonPath("$.avatarThumbnailUrl").value("/uploads/avatars/avatar_user_10_12345_thumb.jpg"));
    }

    @Test
    @DisplayName("S2-03: Xoá ảnh đại diện thành công -> 200 OK")
    void removeAvatar_Authenticated_Returns200() throws Exception {
        PersonalProfileResponse res = PersonalProfileResponse.builder()
                .id(10L)
                .avatarUrl(null)
                .avatarThumbnailUrl(null)
                .build();

        when(userProfileService.removeAvatar(10L)).thenReturn(res);

        mockMvc.perform(delete("/api/v1/profile/avatar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatarUrl").doesNotExist());
    }
}
