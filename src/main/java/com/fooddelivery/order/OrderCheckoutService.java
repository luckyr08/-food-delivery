package com.fooddelivery.order;

import com.fooddelivery.payment.ChargeRequest;
import com.fooddelivery.payment.ChargeResult;
import com.fooddelivery.payment.PaymentDeclinedException;
import com.fooddelivery.payment.PaymentGateway;
import com.fooddelivery.stockgate.GateReservation;
import com.fooddelivery.stockgate.StockGateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

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
    private final StockGateService stockGate;

    public OrderCheckoutService(OrderService orderService, OrderLifecycleService lifecycle, PaymentGateway gateway,
                                StockGateService stockGate) {
        this.orderService = orderService;
        this.lifecycle = lifecycle;
        this.gateway = gateway;
        this.stockGate = stockGate;
    }

    public PlacementResult checkout(Long customerId, PlaceOrderRequest request, String idempotencyKey) {
        // Step 0 (app.stock-gate.mode=redis, hot items only): losers of a flash sale are rejected here in ~1 ms
        // and never reach MySQL. MySQL's conditional UPDATE in step 1 still decides.
        GateReservation gate = stockGate.admit(customerId, request.items().stream().collect(
                Collectors.toMap(OrderLineRequest::menuItemId, OrderLineRequest::quantity, Integer::sum)));
        PlacementResult reserved;
        try {
            reserved = orderService.placeOrder(customerId, request, idempotencyKey); // step 1
        } catch (RuntimeException e) {
            stockGate.release(gate); // MySQL said no (or failed): the gate gives the units back
            throw e;
        }
        if (reserved.replayed()) {
            stockGate.release(gate); // nothing new was bought
        } else {
            stockGate.confirm(gate); // committed: MySQL's deduction is now the reservation
        }
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
