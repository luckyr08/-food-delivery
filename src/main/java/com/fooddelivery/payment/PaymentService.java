package com.fooddelivery.payment;

import com.fooddelivery.order.Order;
import com.fooddelivery.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Payment state inside order transactions. It never calls the gateway: external calls happen outside any
 * DB transaction (charge: OrderCheckoutService, refund: PaymentRefundHandler via the outbox), so no row lock
 * or connection is held during a network call and a failed commit can't leave money moved without a record.
 */
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;

    public PaymentService(PaymentRepository paymentRepository, OutboxWriter outboxWriter) {
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
    }

    /** Saga step 1 (placement transaction): COD is settled on delivery; online starts INITIATED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment initiate(Order order, PaymentMethod method) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setMethod(method);
        payment.setStatus(method == PaymentMethod.CASH_ON_DELIVERY ? PaymentStatus.PENDING : PaymentStatus.INITIATED);
        return paymentRepository.save(payment);
    }

    /** Saga step 3a: the gateway approved the charge. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void confirm(Order order, String providerRef) {
        Payment payment = require(order);
        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setProviderRef(providerRef);
    }

    /** Saga step 3b: declined, or never charged before the reservation expired. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markFailed(Order order) {
        Payment payment = require(order);
        if (payment.getStatus() == PaymentStatus.INITIATED) {
            payment.setStatus(PaymentStatus.FAILED);
        }
    }

    /**
     * Undo the payment of a rejected/cancelled order. A captured payment becomes REFUND_PENDING plus an outbox
     * event in this same transaction; the relay performs the refund after commit, retrying until it succeeds.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverse(Order order) {
        paymentRepository.findByOrderId(order.getId()).ifPresent(payment -> {
            switch (payment.getStatus()) {
                case SUCCESS -> {
                    payment.setStatus(PaymentStatus.REFUND_PENDING);
                    outboxWriter.paymentRefundRequested(payment.getId());
                }
                case PENDING -> payment.setStatus(PaymentStatus.VOIDED); // COD: nothing was collected
                case INITIATED -> payment.setStatus(PaymentStatus.FAILED);
                default -> {
                    // FAILED / REFUND_PENDING / REFUNDED / VOIDED: nothing to undo
                }
            }
        });
    }

    /** Cash on delivery is collected by the partner at the door. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void settleOnDelivery(Order order) {
        paymentRepository.findByOrderId(order.getId())
                .filter(p -> p.getStatus() == PaymentStatus.PENDING)
                .ifPresent(p -> p.setStatus(PaymentStatus.SUCCESS));
    }

    private Payment require(Order order) {
        return paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalStateException("No payment for order " + order.getId()));
    }
}
