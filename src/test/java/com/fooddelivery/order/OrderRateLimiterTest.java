package com.fooddelivery.order;

import com.fooddelivery.common.error.RetryLaterException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class OrderRateLimiterTest {

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        long millis = 1_000_000;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }
    }

    private final MutableClock clock = new MutableClock();
    private final OrderRateLimiter limiter = new OrderRateLimiter(5, 5, clock); // 5 burst, 1 per 12 s

    @Test
    void allowsBurstThenLimitsWithRetryAfter() {
        for (int i = 0; i < 5; i++) {
            limiter.check(1L);
        }
        RetryLaterException ex = catchThrowableOfType(RetryLaterException.class, () -> limiter.check(1L));
        assertThat(ex.getStatus().value()).isEqualTo(429);
        assertThat(ex.getRetryAfterSeconds()).isEqualTo(12);
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 5; i++) {
            limiter.check(1L);
        }
        clock.millis += 12_000;
        limiter.check(1L); // one token refilled
        assertThatThrownBy(() -> limiter.check(1L)).isInstanceOf(RetryLaterException.class);
    }

    @Test
    void customersHaveSeparateBuckets() {
        for (int i = 0; i < 5; i++) {
            limiter.check(1L);
        }
        limiter.check(2L);
    }
}
