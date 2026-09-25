package com.fooddelivery.payment;

import com.fooddelivery.order.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
public class PaymentService {

    private final PaymentGateway gateway;
    private final PaymentRepository paymentRepository;

    public PaymentService(PaymentGateway gateway, PaymentRepository paymentRepository) {
        this.gateway = gateway;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Must run inside the order-placement transaction (MANDATORY), as its last step.
     * A decline throws -> the whole placement rolls back (stock restored, no order).
     * An approved charge whose transaction later rolls back (e.g. commit fails) is refunded by a
     * rollback hook: the gateway call can't be undone by the DB, so we compensate instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment charge(Order order, PaymentMethod method) {
        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setAmount(order.getTotalAmount());
        payment.setMethod(method);

        if (method == PaymentMethod.CASH_ON_DELIVERY) {
            payment.setStatus(PaymentStatus.PENDING); // collected on delivery
            return paymentRepository.save(payment);
        }

        ChargeResult result = gateway.charge(
                new ChargeRequest(String.valueOf(order.getId()), order.getTotalAmount(), method));
        if (!result.approved()) {
            throw new PaymentDeclinedException(result.failureReason());
        }
        registerRefundOnRollback(result.providerRef(), order);

        payment.setStatus(PaymentStatus.SUCCESS);
        payment.setProviderRef(result.providerRef());
        return paymentRepository.save(payment);
    }

    private void registerRefundOnRollback(String providerRef, Order order) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    log.warn("Order placement rolled back after charge {}; refunding", providerRef);
                    gateway.refund(providerRef, order.getTotalAmount());
                }
            }
        });
    }
}
