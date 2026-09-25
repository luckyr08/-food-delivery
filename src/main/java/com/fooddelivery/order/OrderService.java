package com.fooddelivery.order;

import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.payment.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class OrderService {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_orders_customer_idempotency";

    private final OrderPlacementTx placementTx;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public OrderService(OrderPlacementTx placementTx, OrderRepository orderRepository,
                        PaymentRepository paymentRepository) {
        this.placementTx = placementTx;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Deliberately NOT @Transactional: it wraps the placement transaction from outside.
     * - Deadlock / lock timeout (PessimisticLockingFailureException): the transaction was rolled back
     *   by InnoDB, so the whole placement is retried in a fresh transaction (max 2 retries).
     * - Duplicate idempotency key: a concurrent identical request committed first; return its order.
     */
    @Retryable(includes = PessimisticLockingFailureException.class, maxRetries = 2, delay = 50, jitter = 25)
    public PlacementResult placeOrder(Long customerId, PlaceOrderRequest request, String idempotencyKey) {
        String requestHash = RequestHash.of(request);
        try {
            return placementTx.place(customerId, request, idempotencyKey, requestHash);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null && isIdempotencyViolation(e)) {
                log.info("Concurrent duplicate for idempotency key {}; replaying", idempotencyKey);
                return placementTx.replay(customerId, idempotencyKey, requestHash);
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getForCustomer(Long orderId, Long customerId) {
        Order order = orderRepository.findWithItemsByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return OrderResponse.from(order, paymentRepository.findByOrderId(orderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderSummaryResponse> listForCustomer(Long customerId, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(orderRepository.findByCustomerId(customerId, pageable)
                .map(OrderSummaryResponse::from));
    }

    private static boolean isIdempotencyViolation(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(IDEMPOTENCY_CONSTRAINT);
    }
}
