package com.fooddelivery.order;

import com.fooddelivery.common.error.RetryLaterException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderAdmissionControlTest {

    @Test
    void rejectsFastWhenAllSlotsAreBusyAndAdmitsAgainAfterwards() throws Exception {
        OrderAdmissionControl admission = new OrderAdmissionControl(2, 50);
        CountDownLatch holding = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable slowPlacement = () -> admission.admit(() -> {
            holding.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        CompletableFuture<Void> a = CompletableFuture.runAsync(slowPlacement);
        CompletableFuture<Void> b = CompletableFuture.runAsync(slowPlacement);
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        assertThatThrownBy(() -> admission.admit(() -> "third"))
                .isInstanceOf(RetryLaterException.class)
                .satisfies(e -> assertThat(((RetryLaterException) e).getStatus().value()).isEqualTo(503));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000); // fails fast

        release.countDown();
        CompletableFuture.allOf(a, b).get(5, TimeUnit.SECONDS);
        assertThat(admission.admit(() -> "after")).isEqualTo("after");
        assertThat(admission.peakInFlight()).isEqualTo(2);
        assertThat(admission.rejectedCount()).isEqualTo(1);
    }
}
