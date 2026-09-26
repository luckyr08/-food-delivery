package com.fooddelivery.order;

import com.fooddelivery.payment.ChargeRequest;
import com.fooddelivery.payment.ChargeResult;
import com.fooddelivery.payment.PaymentDeclinedException;
import com.fooddelivery.payment.PaymentGateway;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Order placement as a saga. Deliberately NOT @Transactional:
 *   1. reserve (short transaction): stock + order PAYMENT_PENDING + payment INITIATED, commit;
 *   2. charge the gateway OUTSIDE any transaction (no row locks, no pooled connection held);
 *   3. confirm (order PLACED) or compensate (order CANCELLED, stock released) in a second short transaction.
 * If step 2 times out or the app crashes, the order stays PAYMENT_PENDING and PaymentReconciler resolves it
 * by asking the gateway (charges are idempotent on the order id, so nothing is charged twice).
 */
@Slf4j
@Service
public class OrderCheckoutService {

    private final OrderService orderService;
    private final OrderLifecycleService lifecycle;
    private final PaymentGateway gateway;

    public OrderCheckoutService(OrderService orderService, OrderLifecycleService lifecycle, PaymentGateway gateway) {
        this.orderService = orderService;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
    }

    public PlacementResult checkout(Long customerId, PlaceOrderRequest request, String idempotencyKey) {
        PlacementResult reserved = orderService.placeOrder(customerId, request, idempotencyKey); // step 1
        OrderResponse order = reserved.order();
        if (reserved.replayed() || order.status() != OrderStatus.PAYMENT_PENDING) {
            return reserved; // replay, or cash on delivery (already PLACED)
        }

        ChargeResult result;
        try {
            result = gateway.charge(new ChargeRequest(String.valueOf(order.id()), order.totalAmount(),
                    request.paymentMethod())); // step 2
        } catch (RuntimeException e) {
            // Outcome unknown (timeout, gateway down): don't guess. Stay PAYMENT_PENDING -> 202; the
            // reconciler asks the gateway later and completes or cancels.
            log.warn("Charge for order {} failed with unknown outcome: {}", order.id(), e.getMessage());
            return new PlacementResult(order, false);
        }

        if (result.approved()) {
            return new PlacementResult(lifecycle.completePayment(order.id(), customerId, result.providerRef()), false);
        }
        lifecycle.failPayment(order.id(), customerId, result.failureReason()); // step 3b: compensation
        throw new PaymentDeclinedException(result.failureReason(), order.id());
    }
}
