package com.fooddelivery.payment;

import com.fooddelivery.order.Order;
import com.fooddelivery.order.OrderLifecycleService;
import com.fooddelivery.order.OrderRepository;
import com.fooddelivery.order.OrderStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Applies gateway webhooks. The webhook, the synchronous charge response and the reconciler all converge on the
 * same idempotent steps (completePayment / failPayment are no-ops unless the order is still PAYMENT_PENDING);
 * redelivered events are dropped by their stored event id.
 */
@Slf4j
@Service
public class PaymentWebhookService {

    public enum Outcome { PROCESSED, DUPLICATE, IGNORED, AMOUNT_MISMATCH }

    private final JdbcTemplate jdbc;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderLifecycleService lifecycle;
    private final PaymentService paymentService;

    public PaymentWebhookService(JdbcTemplate jdbc, OrderRepository orderRepository,
                                 PaymentRepository paymentRepository, OrderLifecycleService lifecycle,
                                 PaymentService paymentService) {
        this.jdbc = jdbc;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.lifecycle = lifecycle;
        this.paymentService = paymentService;
    }

    /**
     * One transaction: the event id is recorded together with its effects. If processing fails, both roll back
     * and the gateway's retry processes it again.
     */
    @Transactional
    public Outcome handle(WebhookEvent event) {
        int inserted = jdbc.update("INSERT IGNORE INTO payment_webhook_event (event_id, event_type, order_reference, "
                + "received_at) VALUES (?, ?, ?, UTC_TIMESTAMP(6))", event.eventId(), event.type(), event.orderReference());
        if (inserted == 0) {
            return Outcome.DUPLICATE;
        }
        return switch (event.type()) {
            case "payment.captured" -> captured(event);
            case "payment.failed" -> failed(event);
            case "refund.processed" -> refunded(event);
            default -> {
                log.info("Ignoring webhook type {}", event.type());
                yield Outcome.IGNORED;
            }
        };
    }

    private Outcome captured(WebhookEvent event) {
        Optional<Order> found = order(event);
        if (found.isEmpty()) {
            return Outcome.IGNORED;
        }
        Order order = found.get();
        if (event.amount() == null || event.amount().compareTo(order.getTotalAmount()) != 0) {
            // Never complete an order for the wrong amount; recorded for investigation.
            log.error("Webhook {} amount {} != order {} total {}", event.eventId(), event.amount(), order.getId(),
                    order.getTotalAmount());
            return Outcome.AMOUNT_MISMATCH;
        }
        if (order.getStatus() == OrderStatus.PAYMENT_PENDING) {
            lifecycle.completePayment(order.getId(), null, event.providerRef());
            return Outcome.PROCESSED;
        }
        Payment payment = paymentRepository.findByOrderId(order.getId()).orElse(null);
        if (order.getStatus() == OrderStatus.CANCELLED && payment != null
                && payment.getStatus() == PaymentStatus.FAILED) {
            // Captured after we gave up (reservation expired / declined earlier): give the money back.
            log.warn("Late capture for cancelled order {}; refunding {}", order.getId(), event.providerRef());
            paymentService.refundLateCapture(order, event.providerRef());
            return Outcome.PROCESSED;
        }
        return Outcome.IGNORED; // already confirmed by the charge response or the reconciler
    }

    private Outcome failed(WebhookEvent event) {
        return order(event).map(order -> {
            lifecycle.failPayment(order.getId(), null, event.reason() == null ? "declined" : event.reason());
            return Outcome.PROCESSED;
        }).orElse(Outcome.IGNORED);
    }

    private Outcome refunded(WebhookEvent event) {
        return paymentRepository.findByProviderRef(event.providerRef())
                .filter(p -> p.getStatus() == PaymentStatus.REFUND_PENDING)
                .map(p -> {
                    p.setStatus(PaymentStatus.REFUNDED);
                    return Outcome.PROCESSED;
                }).orElse(Outcome.IGNORED);
    }

    private Optional<Order> order(WebhookEvent event) {
        try {
            Optional<Order> order = orderRepository.findById(Long.parseLong(event.orderReference()));
            if (order.isEmpty()) {
                log.warn("Webhook {} for unknown order {}", event.eventId(), event.orderReference());
            }
            return order;
        } catch (NumberFormatException e) {
            log.warn("Webhook {} with invalid order reference {}", event.eventId(), event.orderReference());
            return Optional.empty();
        }
    }
}
