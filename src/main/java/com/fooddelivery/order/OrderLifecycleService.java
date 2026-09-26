package com.fooddelivery.order;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.delivery.DeliveryPartner;
import com.fooddelivery.delivery.DeliveryPartnerRepository;
import com.fooddelivery.menu.MenuItemRepository;
import com.fooddelivery.payment.PaymentRepository;
import com.fooddelivery.payment.PaymentService;
import com.fooddelivery.restaurant.RestaurantOwnerService;
import com.fooddelivery.stockgate.StockGateService;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;

/**
 * Every status change goes through {@link #transition}: state-machine check, the change itself,
 * side effects (restock, payment reversal) and the history row — all in one transaction.
 * Concurrent changes to the same order are caught by the Order's @Version (optimistic locking):
 * the loser's transaction, including its side effects, rolls back with 409.
 */
@Service
public class OrderLifecycleService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final MenuItemRepository menuItemRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final RestaurantOwnerService restaurantOwnerService;
    private final UserRepository userRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final ApplicationEventPublisher events;
    private final StockGateService stockGate;

    public OrderLifecycleService(OrderRepository orderRepository, OrderStatusHistoryRepository historyRepository,
                                 MenuItemRepository menuItemRepository, PaymentRepository paymentRepository,
                                 PaymentService paymentService, RestaurantOwnerService restaurantOwnerService,
                                 UserRepository userRepository, DeliveryPartnerRepository partnerRepository,
                                 ApplicationEventPublisher events, StockGateService stockGate) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.menuItemRepository = menuItemRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.restaurantOwnerService = restaurantOwnerService;
        this.userRepository = userRepository;
        this.partnerRepository = partnerRepository;
        this.events = events;
        this.stockGate = stockGate;
    }

    // ---- entry points per role ----

    @Transactional
    public OrderResponse ownerUpdate(Long restaurantId, Long orderId, Long ownerId, OrderStatus to, String reason) {
        restaurantOwnerService.requireOwned(restaurantId, ownerId);
        Order order = orderRepository.findWithItemsByIdAndRestaurantId(orderId, restaurantId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return transition(order, to, ownerId, Role.RESTAURANT_OWNER, reason);
    }

    @Transactional
    public OrderResponse customerCancel(Long orderId, Long customerId, String reason) {
        Order order = orderRepository.findWithItemsByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return transition(order, OrderStatus.CANCELLED, customerId, Role.CUSTOMER, reason);
    }

    /** Pickup and delivery; only the partner assigned to the order may do this. */
    @Transactional
    public OrderResponse partnerUpdate(Long orderId, Long partnerUserId, OrderStatus to) {
        DeliveryPartner partner = partnerRepository.findByUserId(partnerUserId)
                .orElseThrow(() -> new NotFoundException("Delivery partner profile for user", partnerUserId));
        Order order = orderRepository.findWithItemsByIdAndDeliveryPartnerId(orderId, partner.getId())
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return transition(order, to, partnerUserId, Role.DELIVERY_PARTNER, null);
    }

    // ---- payment saga (system transitions) ----

    /** Saga step 3a: charge approved. No-op if the order was already resolved (e.g. by the reconciler). */
    @Transactional
    public OrderResponse completePayment(long orderId, Long actorId, String providerRef) {
        Order order = orderRepository.findWithItemsById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return response(order);
        }
        paymentService.confirm(order, providerRef);
        return transition(order, OrderStatus.PLACED, actorId, Role.SYSTEM, null);
    }

    /** Saga step 3b (compensation): declined or expired -> cancel, release the stock. */
    @Transactional
    public OrderResponse failPayment(long orderId, Long actorId, String reason) {
        Order order = orderRepository.findWithItemsById(orderId).orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.PAYMENT_PENDING) {
            return response(order);
        }
        paymentService.markFailed(order);
        return transition(order, OrderStatus.CANCELLED, actorId, Role.SYSTEM, "Payment failed: " + reason);
    }

    @Transactional
    public OrderResponse adminCancel(Long orderId, Long adminId, String reason) {
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        requireReason(reason, "Admin cancellations need a reason");
        return transition(order, OrderStatus.CANCELLED, adminId, Role.ADMIN, reason);
    }

    // ---- the one place a status changes ----

    OrderResponse transition(Order order, OrderStatus to, Long actorId, Role role, String reason) {
        OrderStatus from = order.getStatus();
        OrderStateMachine.check(from, to, role);
        if (to == OrderStatus.REJECTED) {
            requireReason(reason, "A reason is required to reject an order");
        }

        order.setStatus(to);
        // Flush now so the @Version check (UPDATE ... WHERE version = ?) runs before any side effect.
        orderRepository.flush();

        // Releasing locks orders (flushed above) then the partner. A claim locks partner then order, but only
        // continues past the partner if they were AVAILABLE, while we only release a BUSY partner — so the
        // two can never wait on each other.
        if (to == OrderStatus.DELIVERED) {
            paymentService.settleOnDelivery(order); // COD collected at the door
            releasePartner(order);
        }
        if (to == OrderStatus.REJECTED || to == OrderStatus.CANCELLED) {
            if (OrderStateMachine.restocksOnCancel(from)) {
                restock(order);
            }
            releasePartner(order); // keeps delivery_partner_id on the order as a record
            paymentService.reverse(order); // last: an external call
        }

        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setChangedBy(actorId == null ? null : userRepository.getReferenceById(actorId)); // null = system
        history.setNote(reason == null || reason.isBlank() ? null : reason.trim());
        historyRepository.save(history);

        // An order whose payment never went through was never shown to anyone: nothing to announce.
        if (from != OrderStatus.PAYMENT_PENDING || to != OrderStatus.CANCELLED) {
            events.publishEvent(OrderEvent.of(OrderEvent.Kind.STATUS_CHANGED, order, from, actorId, history.getNote()));
        }
        return OrderResponse.from(order, paymentRepository.findByOrderId(order.getId()).orElse(null));
    }

    private OrderResponse response(Order order) {
        return OrderResponse.from(order, paymentRepository.findByOrderId(order.getId()).orElse(null));
    }

    private void releasePartner(Order order) {
        if (order.getDeliveryPartner() != null) {
            partnerRepository.markAvailable(order.getDeliveryPartner().getId());
        }
    }

    /** Same ascending-id lock order as placement, so restock and placement can't deadlock. */
    private void restock(Order order) {
        order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getMenuItem().getId()))
                .forEach(item -> {
                    menuItemRepository.restock(item.getMenuItem().getId(), item.getQuantity());
                    stockGate.afterMysqlRestock(item.getMenuItem()); // Redis counter re-synced after commit
                });
    }

    private static void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.REASON_REQUIRED, message);
        }
    }
}
