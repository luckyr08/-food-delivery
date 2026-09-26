package com.fooddelivery.order;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the reconciler in the background. Disabled in tests, which call PaymentReconciler directly. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.reconciler.enabled", havingValue = "true", matchIfMissing = true)
class PaymentReconcilerScheduler {

    private final PaymentReconciler reconciler;

    PaymentReconcilerScheduler(PaymentReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${app.payment.reconciler.interval-ms:60000}")
    void run() {
        try {
            PaymentReconciler.Outcome outcome = reconciler.reconcile();
            if (outcome.completed() + outcome.cancelled() + outcome.left() > 0) {
                log.info("Payment reconciler: {}", outcome);
            }
        } catch (RuntimeException e) {
            log.error("Payment reconciler run failed", e);
        }
    }
}
