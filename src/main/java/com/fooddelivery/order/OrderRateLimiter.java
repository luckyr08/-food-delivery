package com.fooddelivery.order;

import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.RetryLaterException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-customer token bucket on order placement (bots, double-tappers, runaway client retries).
 * In memory, so per instance; production would do this at the API gateway or with Redis.
 */
@Component
public class OrderRateLimiter {

    private final int capacity;
    private final double tokensPerMs;
    private final Clock clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public OrderRateLimiter(@Value("${app.order.rate-limit.capacity:5}") int capacity,
                            @Value("${app.order.rate-limit.refill-per-minute:5}") int refillPerMinute, Clock clock) {
        this.capacity = capacity;
        this.tokensPerMs = refillPerMinute / 60_000.0;
        this.clock = clock;
    }

    public void check(long customerId) {
        long now = clock.millis();
        long waitMs = buckets.computeIfAbsent(customerId, id -> new Bucket(capacity, now)).tryTake(now);
        if (waitMs > 0) {
            throw new RetryLaterException(HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED,
                    "Too many orders, please slow down", (waitMs + 999) / 1000);
        }
    }

    /** Full buckets carry no information; drop them so the map doesn't grow forever. */
    @Scheduled(fixedDelay = 300_000)
    void evictIdle() {
        long now = clock.millis();
        buckets.entrySet().removeIf(e -> e.getValue().isFull(now));
    }

    private final class Bucket {
        private double tokens;
        private long lastRefill;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefill = now;
        }

        /** @return 0 if a token was taken, otherwise ms until the next token. */
        synchronized long tryTake(long now) {
            refill(now);
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) / tokensPerMs);
        }

        synchronized boolean isFull(long now) {
            refill(now);
            return tokens >= capacity;
        }

        private void refill(long now) {
            tokens = Math.min(capacity, tokens + (now - lastRefill) * tokensPerMs);
            lastRefill = now;
        }
    }
}
