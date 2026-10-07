package com.erp.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chống dò email ở màn "Quên mật khẩu", tính theo từng máy (địa chỉ IP):
 * nhập email CHƯA ĐĂNG KÝ sai 5 lần -> khoá tạm 1 phút.
 * Nhập đúng email (gửi mail thành công) thì đếm lại từ đầu.
 *
 * Lưu trong bộ nhớ (reset khi khởi động lại server) - đủ dùng cho quy mô dự án.
 */
@Component
public class ForgotPasswordFailureLimiter {

    private final Clock clock;
    private final int maxFailures;
    private final Duration lockDuration;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static final class State {
        int failures;
        Instant lockedUntil;
    }

    @Autowired
    public ForgotPasswordFailureLimiter(
            @Value("${erp.app.forgot-password.max-wrong-attempts:5}") int maxFailures,
            @Value("${erp.app.forgot-password.lock-seconds:60}") long lockSeconds) {
        this(Clock.systemUTC(), maxFailures, lockSeconds);
    }

    /** Dùng trong test: mặc định sai 5 lần khoá 1 phút. */
    ForgotPasswordFailureLimiter(Clock clock) {
        this(clock, 5, 60);
    }

    ForgotPasswordFailureLimiter(Clock clock, int maxFailures, long lockSeconds) {
        this.clock = clock;
        this.maxFailures = maxFailures;
        this.lockDuration = Duration.ofSeconds(lockSeconds);
    }

    /** Số giây còn bị khoá. Trả về 0 nếu không bị khoá. */
    public long secondsLocked(String clientKey) {
        State state = states.get(key(clientKey));
        if (state == null) {
            return 0;
        }
        synchronized (state) {
            Instant now = clock.instant();
            if (state.lockedUntil == null) {
                return 0;
            }
            if (!state.lockedUntil.isAfter(now)) {
                // Hết thời gian khoá -> cho nhập lại từ đầu
                state.lockedUntil = null;
                state.failures = 0;
                return 0;
            }
            long millis = Duration.between(now, state.lockedUntil).toMillis();
            return Math.max(1, (millis + 999) / 1000);
        }
    }

    /**
     * Ghi nhận một lần nhập email chưa đăng ký.
     * @return số lần thử còn lại (0 nghĩa là vừa bị khoá).
     */
    public int recordFailure(String clientKey) {
        State state = states.computeIfAbsent(key(clientKey), k -> new State());
        synchronized (state) {
            state.failures++;
            if (state.failures >= maxFailures) {
                state.lockedUntil = clock.instant().plus(lockDuration);
                return 0;
            }
            return maxFailures - state.failures;
        }
    }

    /** Nhập đúng email -> xoá bộ đếm sai. */
    public void reset(String clientKey) {
        states.remove(key(clientKey));
    }

    private String key(String clientKey) {
        return clientKey == null ? "unknown" : clientKey;
    }
}
