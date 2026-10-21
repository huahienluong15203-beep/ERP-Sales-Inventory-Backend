package com.erp.backend.security;

import com.erp.backend.entity.User;
import com.erp.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtUtils jwtUtils;
    @Mock private UserDetailsServiceImpl userDetailsService;
    @Mock private UserRepository userRepository;
    @InjectMocks private JwtAuthenticationFilter filter;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletResponse run(User user, String sid, Date issuedAt) throws Exception {
        when(jwtUtils.validateJwtToken("tok")).thenReturn(true);
        when(jwtUtils.getUsernameFromJwtToken("tok")).thenReturn(user.getUsername());
        when(jwtUtils.getSessionIdFromJwtToken("tok")).thenReturn(sid);
        org.mockito.Mockito.lenient().when(jwtUtils.getIssuedAtFromToken("tok")).thenReturn(issuedAt);
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(UserDetailsImpl.build(user));
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer tok");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    private static User user(String status, LocalDateTime lockUntil, LocalDateTime passwordChangedAt) {
        return User.builder().id(7L).username("sales_rep").password("x").status(status)
                .lockUntil(lockUntil).activeSessionId("s1").passwordChangedAt(passwordChangedAt).build();
    }

    @Test
    @DisplayName("S1-01: người khác nhập sai 5 lần (tạm khoá 15 phút) -> người đang làm việc KHÔNG bị đá ra")
    void tempLock_keepsActiveSession() throws Exception {
        MockHttpServletResponse res = run(user("LOCKED", LocalDateTime.now().plusMinutes(15), null), "s1", new Date());

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    @Test
    @DisplayName("S1-10: Admin khoá tài khoản -> phiên bị thu hồi ngay (401 ACCOUNT_LOCKED)")
    void adminLock_revokesSession() throws Exception {
        MockHttpServletResponse res = run(user("LOCKED", null, null), "s1", new Date());

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentAsString()).contains("ACCOUNT_LOCKED");
    }

    @Test
    @DisplayName("S1-04: đổi mật khẩu -> phiên hiện tại (đúng sid) vẫn dùng tiếp, không bị đá ra")
    void passwordChanged_currentSessionStays() throws Exception {
        Date issuedTenMinutesAgo = new Date(System.currentTimeMillis() - 10 * 60 * 1000);
        MockHttpServletResponse res = run(user("ACTIVE", null, LocalDateTime.now()), "s1", issuedTenMinutesAgo);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }
}
