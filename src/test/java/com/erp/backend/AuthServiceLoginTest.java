package com.erp.backend;

import com.erp.backend.dto.LoginRequest;
import com.erp.backend.dto.LoginResponse;
import com.erp.backend.entity.User;
import com.erp.backend.repository.PasswordResetTokenRepository;
import com.erp.backend.repository.UserRepository;
import com.erp.backend.security.JwtUtils;
import com.erp.backend.service.AuthService;
import com.erp.backend.service.EmailService;
import com.erp.backend.service.ForgotPasswordRateLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * S1-01 + quy tắc 10: đăng nhập thất bại vì bất kỳ lý do gì đều trả về CÙNG một thông báo,
 * không tiết lộ tài khoản có tồn tại hay không, không cho dò mật khẩu khi đang bị khoá.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLoginTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtils jwtUtils;
    @Mock private EmailService emailService;
    @Mock private ForgotPasswordRateLimiter forgotPasswordRateLimiter;

    @InjectMocks private AuthService authService;

    private LoginRequest login(String username, String password) {
        LoginRequest req = new LoginRequest();
        req.setUsername(username);
        req.setPassword(password);
        return req;
    }

    private User user(String status, int failedAttempts, LocalDateTime lockUntil) {
        return User.builder()
                .id(1L)
                .username("sales01")
                .password("hashed")
                .status(status)
                .failedLoginAttempts(failedAttempts)
                .lockUntil(lockUntil)
                .build();
    }

    @Test
    @DisplayName("S1-01: Tài khoản không tồn tại -> thông báo chung")
    void unknownUser_genericMessage() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.authenticateUser(login("ghost", "whatever")));

        assertEquals(AuthService.LOGIN_FAILED_MESSAGE, ex.getMessage());
    }

    @Test
    @DisplayName("S1-01: Sai mật khẩu -> CÙNG thông báo như tài khoản không tồn tại, không báo số lần thử còn lại")
    void wrongPassword_sameMessageAsUnknownUser() {
        User u = user("ACTIVE", 1, null);
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("sai", "hashed")).thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.authenticateUser(login("sales01", "sai")));

        assertEquals(AuthService.LOGIN_FAILED_MESSAGE, ex.getMessage());
        assertFalse(ex.getMessage().contains("lần thử"));
        assertEquals(2, u.getFailedLoginAttempts());
        verify(userRepository).save(u);
    }

    @Test
    @DisplayName("S1-01: Sai lần thứ 5 -> tạm khoá 15 phút, thông báo vẫn là thông báo chung")
    void fifthWrongPassword_locks15Minutes() {
        User u = user("ACTIVE", 4, null);
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("sai", "hashed")).thenReturn(false);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.authenticateUser(login("sales01", "sai")));

        assertEquals(AuthService.LOGIN_FAILED_MESSAGE, ex.getMessage());
        assertEquals("LOCKED", u.getStatus());
        assertNotNull(u.getLockUntil());
        assertTrue(u.getLockUntil().isAfter(LocalDateTime.now().plusMinutes(14)));
    }

    @Test
    @DisplayName("S1-01: Đang tạm khoá -> từ chối với thông báo chung, KHÔNG kiểm tra mật khẩu (chống dò mật khẩu)")
    void tempLocked_rejectedWithoutCheckingPassword() {
        User u = user("LOCKED", 5, LocalDateTime.now().plusMinutes(10));
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(u));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.authenticateUser(login("sales01", "dung-mat-khau")));

        assertEquals(AuthService.LOGIN_FAILED_MESSAGE, ex.getMessage());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(jwtUtils, never()).generateTokenFromUsernameAndSession(anyString(), anyString());
    }

    @Test
    @DisplayName("S1-10: Bị Admin khoá -> thông báo chung, không kiểm tra mật khẩu")
    void adminLocked_genericMessage() {
        User u = user("LOCKED", 0, null);
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(u));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> authService.authenticateUser(login("sales01", "dung-mat-khau")));

        assertEquals(AuthService.LOGIN_FAILED_MESSAGE, ex.getMessage());
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    @DisplayName("S1-01: Hết 15 phút tạm khoá + đúng mật khẩu -> đăng nhập được, đặt lại bộ đếm")
    void tempLockExpired_correctPassword_loginSucceeds() {
        User u = user("LOCKED", 5, LocalDateTime.now().minusMinutes(1));
        when(userRepository.findByUsername("sales01")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("dung", "hashed")).thenReturn(true);
        when(jwtUtils.generateTokenFromUsernameAndSession(eq("sales01"), anyString())).thenReturn("jwt");

        LoginResponse res = authService.authenticateUser(login("sales01", "dung"));

        assertEquals("jwt", res.getAccessToken());
        assertEquals("ACTIVE", u.getStatus());
        assertEquals(0, u.getFailedLoginAttempts());
        assertNull(u.getLockUntil());
        verify(userRepository, atLeastOnce()).save(any(User.class));
    }
}
