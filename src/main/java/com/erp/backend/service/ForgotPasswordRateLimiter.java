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
 * Chống spam gửi email "Quên mật khẩu" theo từng địa chỉ email:
 * hai lần gửi liên tiếp phải cách nhau ít nhất 1 phút (gửi lại nhiều lần cũng chỉ phải đợi 1 phút).
 * Thời gian chờ đổi được trong application.properties (erp.app.forgot-password.cooldown-seconds).
 *
 * Lưu trong bộ nhớ (reset khi khởi động lại server) - đủ dùng cho quy mô dự án.
 */
@Component
public class ForgotPasswordRateLimiter {

    private final Clock clock;
    private final Duration cooldown;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();

    @Autowired
    public ForgotPasswordRateLimiter(@Value("${erp.app.forgot-password.cooldown-seconds:60}") long cooldownSeconds) {
        this(Clock.systemUTC(), cooldownSeconds);
    }

    /** Dùng trong test: mặc định đợi 60 giây. */
    ForgotPasswordRateLimiter(Clock clock) {
        this(clock, 60);
    }

    ForgotPasswordRateLimiter(Clock clock, long cooldownSeconds) {
        this.clock = clock;
        this.cooldown = Duration.ofSeconds(cooldownSeconds);
    }

    /** Số giây phải chờ giữa 2 lần gửi (để Frontend hiển thị đếm ngược). */
    public long getCooldownSeconds() {
        return cooldown.getSeconds();
    }

    /** Số giây còn phải đợi trước khi được gửi tiếp. Trả về 0 nếu được gửi ngay. */
    public long secondsUntilAllowed(String email) {
        Instant last = lastSent.get(key(email));
        if (last == null) {
            return 0;
        }
        Instant now = clock.instant();
        Instant allowedAt = last.plus(cooldown);
        if (!allowedAt.isAfter(now)) {
            return 0;
        }
        long millis = Duration.between(now, allowedAt).toMillis();
        return Math.max(1, (millis + 999) / 1000); // làm tròn lên, tối thiểu 1 giây
    }

    /** Ghi nhận một lần đã gửi email cho địa chỉ này. */
    public void recordSent(String email) {
        lastSent.put(key(email), clock.instant());
    }

    private String key(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
