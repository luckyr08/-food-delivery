package com.fooddelivery.payment;

import com.fooddelivery.outbox.OutboxHandler;
import com.fooddelivery.outbox.OutboxWriter;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Performs refunds requested by cancelling transactions, after they committed (outbox, at-least-once).
 * Safe to repeat: the gateway refund is idempotent on the provider reference, and REFUNDED rows are skipped.
 * A gateway failure throws -> the relay retries these events with backoff.
 */
@Component
public class PaymentRefundHandler implements OutboxHandler {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;

    public PaymentRefundHandler(PaymentRepository paymentRepository, PaymentGateway gateway) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
    }

    @Override
    public String aggregateType() {
        return OutboxWriter.PAYMENT;
    }

    @Override
    public void handle(Set<Long> paymentIds) {
        for (Payment payment : paymentRepository.findAllById(paymentIds)) {
            if (payment.getStatus() == PaymentStatus.REFUND_PENDING) {
                gateway.refund(payment.getProviderRef(), payment.getAmount());
                payment.setStatus(PaymentStatus.REFUNDED); // flushed when the relay's transaction commits
            }
        }
    }
}
