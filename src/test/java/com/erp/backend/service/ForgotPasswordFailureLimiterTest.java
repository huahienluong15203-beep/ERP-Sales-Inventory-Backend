package com.erp.backend.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ForgotPasswordFailureLimiterTest {

    /** Đồng hồ giả để "tua" thời gian trong test. */
    private static class FakeClock extends Clock {
        private Instant now = Instant.parse("2026-09-30T13:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final FakeClock clock = new FakeClock();
    private final ForgotPasswordFailureLimiter limiter = new ForgotPasswordFailureLimiter(clock);

    @Test
    @DisplayName("Sai 4 lần -> chưa bị khoá, báo đúng số lần thử còn lại")
    void fourFailures_notLocked() {
        assertThat(limiter.recordFailure("ip")).isEqualTo(4);
        assertThat(limiter.recordFailure("ip")).isEqualTo(3);
        assertThat(limiter.recordFailure("ip")).isEqualTo(2);
        assertThat(limiter.recordFailure("ip")).isEqualTo(1);
        assertThat(limiter.secondsLocked("ip")).isZero();
    }

    @Test
    @DisplayName("Sai lần thứ 5 -> khoá đúng 1 phút, hết 1 phút thì nhập lại được từ đầu")
    void fifthFailure_locksOneMinute() {
        for (int i = 0; i < 4; i++) {
            limiter.recordFailure("ip");
        }
        assertThat(limiter.recordFailure("ip")).isZero();
        assertThat(limiter.secondsLocked("ip")).isEqualTo(60);

        clock.advance(Duration.ofSeconds(20));
        assertThat(limiter.secondsLocked("ip")).isEqualTo(40);

        clock.advance(Duration.ofSeconds(40));
        assertThat(limiter.secondsLocked("ip")).isZero();
        assertThat(limiter.recordFailure("ip")).isEqualTo(4); // đếm lại từ đầu
    }

    @Test
    @DisplayName("Nhập đúng email -> xoá bộ đếm sai")
    void reset_clearsFailures() {
        limiter.recordFailure("ip");
        limiter.recordFailure("ip");
        limiter.reset("ip");
        assertThat(limiter.recordFailure("ip")).isEqualTo(4);
    }

    @Test
    @DisplayName("Máy khác nhau không ảnh hưởng nhau")
    void differentClients_independent() {
        for (int i = 0; i < 5; i++) {
            limiter.recordFailure("may-A");
        }
        assertThat(limiter.secondsLocked("may-A")).isPositive();
        assertThat(limiter.secondsLocked("may-B")).isZero();
    }
}
