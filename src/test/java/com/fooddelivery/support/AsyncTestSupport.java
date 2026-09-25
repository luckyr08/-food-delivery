package com.fooddelivery.support;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;

import static org.awaitility.Awaitility.await;

public final class AsyncTestSupport {

    private AsyncTestSupport() {
    }

    /**
     * Waits until no notification task is running or queued. Called after each test so an async task
     * from one test can't write rows after the next test has cleaned the database.
     */
    public static void awaitIdle(ThreadPoolTaskExecutor executor) {
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() ->
                executor.getActiveCount() == 0 && executor.getThreadPoolExecutor().getQueue().isEmpty());
    }
}
