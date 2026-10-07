package com.erp.backend.security;

import com.erp.backend.entity.User;
import com.erp.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Date;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtUtils jwtUtils;
    private final UserDetailsServiceImpl userDetailsService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 1. Trích xuất chuỗi Token từ Header "Authorization: Bearer <token>"
            String jwt = parseJwt(request);

            // 2. Nếu có token và token hợp lệ
            if (jwt != null && jwtUtils.validateJwtToken(jwt)) {
                String username = jwtUtils.getUsernameFromJwtToken(jwt);
                String jwtSessionId = jwtUtils.getSessionIdFromJwtToken(jwt);

                // 3. Lấy thông tin user và quyền từ Database
                UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                // 4. Reloading current account state invalidates existing JWTs after an admin lock.
                if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()) {
                    logger.warn("Tài khoản '{}' đã bị khoá hoặc vô hiệu hoá bởi Quản trị viên.", username);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Tài khoản của bạn đã bị khoá bởi Quản trị viên. Phiên làm việc đã bị thu hồi!\",\"code\":\"ACCOUNT_LOCKED\"}");
                    return;
                }

                User dbUser = userRepository.findByUsername(username).orElse(null);
                if (dbUser != null && ("LOCKED".equalsIgnoreCase(dbUser.getStatus()) || (dbUser.getLockUntil() != null && dbUser.getLockUntil().isAfter(java.time.LocalDateTime.now())))) {
                    logger.warn("Tài khoản '{}' trạng thái trong DB đang bị khoá.", username);
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Tài khoản của bạn đã bị khoá bởi Quản trị viên. Phiên làm việc đã bị thu hồi!\",\"code\":\"ACCOUNT_LOCKED\"}");
                    return;
                }

                // 5. Kiểm tra Đơn phiên làm việc (Single Active Session): 1 nick chỉ 1 phiên duy nhất
                if (dbUser != null) {
                        String currentActiveSessionId = dbUser.getActiveSessionId();
                        boolean isSessionInvalid = false;
                        if (currentActiveSessionId != null && (jwtSessionId == null || !currentActiveSessionId.equals(jwtSessionId))) {
                            isSessionInvalid = true;
                        } else if (currentActiveSessionId == null && jwtSessionId != null) {
                            isSessionInvalid = true;
                        }

                        if (isSessionInvalid) {
                            logger.warn("Phiên làm việc của '{}' đã bị thu hồi do đăng nhập ở thiết bị/cửa sổ khác hoặc đã đăng xuất.", username);
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"Phiên làm việc của bạn đã hết hạn do tài khoản đã được đăng nhập ở một thiết bị hoặc phiên làm việc khác. Vui lòng đăng nhập lại!\",\"code\":\"SESSION_SUPERSEDED\"}");
                            return;
                        }
                    }

                    // 6. S1-04: Thu hồi phiên cũ nếu mật khẩu đã bị đổi sau khi token được cấp.
                    //    Token issuedAt phải SAU thời điểm đổi mật khẩu gần nhất.
                    boolean tokenIsValid = true;
                    Date issuedAt = jwtUtils.getIssuedAtFromToken(jwt);
                    if (issuedAt != null && dbUser != null && dbUser.getPasswordChangedAt() != null) {
                        long changedAtEpochSec = dbUser.getPasswordChangedAt()
                                .atZone(ZoneId.systemDefault()).toEpochSecond();
                        long issuedAtEpochSec = issuedAt.getTime() / 1000;
                        if (issuedAtEpochSec < changedAtEpochSec - 1) {
                            // Token được cấp TRƯỚC khi đổi mật khẩu -> thu hồi
                            logger.info("Token của '{}' bị thu hồi do đổi mật khẩu sau khi phát hành.", username);
                            tokenIsValid = false;
                        }
                    }

                    if (tokenIsValid) {
                        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities());
                        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                        // Lưu vào SecurityContext để các Controller kiểm tra quyền (@PreAuthorize)
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    }
            }
        } catch (Exception e) {
            logger.error("Không thể xác thực người dùng: {}", e.getMessage());
        }

        filterChain.doFilter(request, response);
    }

    // Hàm phụ trợ cắt bỏ chữ "Bearer " để lấy chuỗi token nguyên bản
    private String parseJwt(HttpServletRequest request) {
        String headerAuth = request.getHeader("Authorization");

        if (StringUtils.hasText(headerAuth) && headerAuth.startsWith("Bearer ")) {
            return headerAuth.substring(7);
        }

        return null;
    }
}
