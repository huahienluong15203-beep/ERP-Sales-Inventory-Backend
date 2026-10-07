package com.erp.backend.controller;

import com.erp.backend.dto.LoginRequest;
import com.erp.backend.dto.LoginResponse;
import com.erp.backend.dto.MessageResponse;
import com.erp.backend.service.AuthService;
import com.erp.backend.service.ForgotPasswordRateLimiter;
import lombok.RequiredArgsConstructor;
import com.erp.backend.dto.ForgotPasswordRequest;
import com.erp.backend.dto.ResetPasswordRequest;

import com.erp.backend.dto.ChangePasswordRequest;
import com.erp.backend.exception.TooManyRequestsException;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import com.erp.backend.security.UserDetailsImpl;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final ForgotPasswordRateLimiter forgotPasswordRateLimiter;

    // 1. API ĐĂNG NHẬP (Story S1-01)
    @PostMapping("/login")
    public ResponseEntity<?> authenticateUser(@RequestBody LoginRequest loginRequest) {
        try {
            LoginResponse response = authService.authenticateUser(loginRequest);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            // Trả về thông báo lỗi rõ ràng (ví dụ: sai pass hoặc bị khoá 15 phút)
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

    // 2. API ĐĂNG XUẤT (Story S1-02 & Đơn phiên làm việc)
    @PostMapping("/logout")
    public ResponseEntity<?> logoutUser(@AuthenticationPrincipal UserDetailsImpl userDetails) {
        if (userDetails != null) {
            authService.logout(userDetails.getUsername());
        }
        return ResponseEntity.ok(new MessageResponse("Đăng xuất thành công!"));
    }

    // 3. API QUÊN MẬT KHẨU (Gửi mail đặt lại mật khẩu)
    @PostMapping("/forgot-password")
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest request,
                                            HttpServletRequest httpRequest) {
        try {
            String message = authService.forgotPassword(request, httpRequest.getRemoteAddr());
            // Kèm số giây phải chờ trước khi gửi lại để Frontend đếm ngược đúng cấu hình
            return ResponseEntity.ok(Map.of(
                    "message", message,
                    "cooldownSeconds", forgotPasswordRateLimiter.getCooldownSeconds()));
        } catch (TooManyRequestsException e) {
            // 429: gửi quá nhanh -> báo số giây phải đợi để Frontend đếm ngược
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                    .body(Map.of("message", e.getMessage(), "retryAfterSeconds", e.getRetryAfterSeconds()));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

    // 4. API ĐẶT LẠI MẬT KHẨU MỚI
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            String message = authService.resetPassword(request);
            return ResponseEntity.ok(new MessageResponse(message));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

    // 5. API ĐỔI MẬT KHẨU KHI ĐANG ĐĂNG NHẬP
    @PostMapping("/change-password")
    public ResponseEntity<?> changePassword(
            @AuthenticationPrincipal UserDetailsImpl userDetails,
            @RequestBody ChangePasswordRequest request) {
        if (userDetails == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new MessageResponse("Vui lòng đăng nhập để thực hiện đổi mật khẩu!"));
        }

        try {
            String message = authService.changePassword(userDetails.getId(), request);
            return ResponseEntity.ok(new MessageResponse(message));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(new MessageResponse(e.getMessage()));
        }
    }

}
