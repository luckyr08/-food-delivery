package com.fooddelivery.order;

import com.fooddelivery.payment.GatewayChargeStatus;
import com.fooddelivery.payment.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Resolves orders stuck in PAYMENT_PENDING (gateway timeout, crash between the saga's steps).
 * For each order older than min-age it asks the gateway: charged -> complete it; not charged and older than
 * the reservation expiry -> cancel it and release the stock; gateway unreachable -> leave it for the next
 * run (never cancel blind: the customer might have been charged).
 */
@Slf4j
@Component
public class PaymentReconciler {

    private final OrderRepository orderRepository;
    private final OrderLifecycleService lifecycle;
    private final PaymentGateway gateway;
    private final Clock clock;
    private final Duration minAge;
    private final Duration reservationExpiry;

    public PaymentReconciler(OrderRepository orderRepository, OrderLifecycleService lifecycle, PaymentGateway gateway,
                             Clock clock,
                             @Value("${app.payment.reconciler.min-age-seconds:60}") long minAgeSeconds,
                             @Value("${app.payment.reservation-expiry-minutes:15}") long expiryMinutes) {
        this.orderRepository = orderRepository;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.clock = clock;
        this.minAge = Duration.ofSeconds(minAgeSeconds);
        this.reservationExpiry = Duration.ofMinutes(expiryMinutes);
    }

    public record Outcome(int completed, int cancelled, int left) {
    }

    public Outcome reconcile() {
        return reconcileAt(clock.instant());
    }

    /** Package-visible with an explicit "now" so tests can move time forward. */
    Outcome reconcileAt(Instant now) {
        int completed = 0;
        int cancelled = 0;
        int left = 0;
        for (Order order : orderRepository.findTop100ByStatusAndCreatedAtBeforeOrderByIdAsc(
                OrderStatus.PAYMENT_PENDING, now.minus(minAge))) {
            try {
                GatewayChargeStatus status = gateway.status(String.valueOf(order.getId()));
                if (status.charged()) {
                    lifecycle.completePayment(order.getId(), null, status.providerRef());
                    completed++;
                } else if (order.getCreatedAt().isBefore(now.minus(reservationExpiry))) {
                    lifecycle.failPayment(order.getId(), null, "reservation expired");
                    cancelled++;
                } else {
                    left++;
                }
            } catch (OptimisticLockingFailureException e) {
                left++; // resolved concurrently by the checkout itself; next run sees the final state
            } catch (RuntimeException e) {
                log.warn("Reconciling order {} failed (gateway unreachable?), will retry: {}", order.getId(),
                        e.getMessage());
                left++;
            }
        }
        return new Outcome(completed, cancelled, left);
    }
}
