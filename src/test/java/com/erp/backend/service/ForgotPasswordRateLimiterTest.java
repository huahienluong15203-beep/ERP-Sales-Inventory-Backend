package com.erp.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ForgotPasswordRateLimiterTest {

    /** Đồng hồ giả để "tua" thời gian trong test. */
    private static class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final ForgotPasswordRateLimiter limiter = new ForgotPasswordRateLimiter(clock);

    @Test
    @DisplayName("Lần đầu gửi -> được phép ngay")
    void firstRequest_allowed() {
        assertThat(limiter.secondsUntilAllowed("a@erp.com")).isZero();
    }

    @Test
    @DisplayName("Gửi lại trong vòng 60 giây -> phải đợi đủ 60 giây")
    void withinCooldown_mustWait() {
        limiter.recordSent("a@erp.com");
        clock.advance(Duration.ofSeconds(15));

        assertThat(limiter.secondsUntilAllowed("a@erp.com")).isEqualTo(45);

        clock.advance(Duration.ofSeconds(45));
        assertThat(limiter.secondsUntilAllowed("a@erp.com")).isZero();
    }

    @Test
    @DisplayName("Không phân biệt hoa/thường và khoảng trắng của email")
    void emailKey_isNormalized() {
        limiter.recordSent("  A@ERP.com ");
        assertThat(limiter.secondsUntilAllowed("a@erp.com")).isEqualTo(60);
    }

    @Test
    @DisplayName("Email khác nhau không ảnh hưởng nhau")
    void differentEmails_independent() {
        limiter.recordSent("a@erp.com");
        assertThat(limiter.secondsUntilAllowed("b@erp.com")).isZero();
    }

    @Test
    @DisplayName("Gửi lại nhiều lần liên tục -> mỗi lần chỉ phải đợi tối đa 1 phút, không bị khoá lâu")
    void manySends_onlyOneMinuteEachTime() {
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.secondsUntilAllowed("a@erp.com")).isZero();
            limiter.recordSent("a@erp.com");
            assertThat(limiter.secondsUntilAllowed("a@erp.com")).isEqualTo(60);
            clock.advance(Duration.ofSeconds(60));
        }
    }
}
