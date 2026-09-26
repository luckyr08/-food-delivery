package com.fooddelivery.order;

import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.RetryLaterException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Bulkhead for order placement: at most N placements run at once per instance (sized below the DB
 * connection pool). Others wait briefly for a slot and are then rejected with a fast 503 + Retry-After,
 * instead of queueing on the pool and row locks until everything times out.
 * Spring 7's @ConcurrencyLimit throttles by blocking callers; we want fail-fast, hence a plain Semaphore.
 */
@Component
public class OrderAdmissionControl {

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long acquireTimeoutMs;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger peakInFlight = new AtomicInteger();
    private final AtomicLong rejected = new AtomicLong();

    public OrderAdmissionControl(@Value("${app.order.admission.max-concurrent:16}") int maxConcurrent,
                                 @Value("${app.order.admission.acquire-timeout-ms:200}") long acquireTimeoutMs) {
        this.permits = new Semaphore(maxConcurrent, true); // fair: first come, first served
        this.maxConcurrent = maxConcurrent;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public <T> T admit(Supplier<T> placement) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            rejected.incrementAndGet();
            throw new RetryLaterException(HttpStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_BUSY,
                    "Ordering is very busy right now, please retry in a moment", 2);
        }
        try {
            peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return placement.get();
        } finally {
            inFlight.decrementAndGet();
            permits.release();
        }
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int peakInFlight() {
        return peakInFlight.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    /** Test support. */
    public void resetStats() {
        peakInFlight.set(0);
        rejected.set(0);
    }
}
