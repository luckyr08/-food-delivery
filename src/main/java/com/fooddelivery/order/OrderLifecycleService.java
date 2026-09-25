package com.fooddelivery.order;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.menu.MenuItemRepository;
import com.fooddelivery.payment.PaymentRepository;
import com.fooddelivery.payment.PaymentService;
import com.fooddelivery.restaurant.RestaurantOwnerService;
import com.fooddelivery.user.Role;
import com.fooddelivery.user.UserRepository;
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

    public OrderLifecycleService(OrderRepository orderRepository, OrderStatusHistoryRepository historyRepository,
                                 MenuItemRepository menuItemRepository, PaymentRepository paymentRepository,
                                 PaymentService paymentService, RestaurantOwnerService restaurantOwnerService,
                                 UserRepository userRepository) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.menuItemRepository = menuItemRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.restaurantOwnerService = restaurantOwnerService;
        this.userRepository = userRepository;
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

        if (to == OrderStatus.REJECTED || to == OrderStatus.CANCELLED) {
            if (OrderStateMachine.restocksOnCancel(from)) {
                restock(order);
            }
            paymentService.reverse(order); // last: an external call
        }

        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(from);
        history.setToStatus(to);
        history.setChangedBy(userRepository.getReferenceById(actorId));
        history.setNote(reason == null || reason.isBlank() ? null : reason.trim());
        historyRepository.save(history);

        return OrderResponse.from(order, paymentRepository.findByOrderId(order.getId()).orElse(null));
    }

    /** Same ascending-id lock order as placement, so restock and placement can't deadlock. */
    private void restock(Order order) {
        order.getItems().stream()
                .sorted(Comparator.comparing(item -> item.getMenuItem().getId()))
                .forEach(item -> menuItemRepository.restock(item.getMenuItem().getId(), item.getQuantity()));
    }

    private static void requireReason(String reason, String message) {
        if (reason == null || reason.isBlank()) {
            throw new BadRequestException(ErrorCode.REASON_REQUIRED, message);
        }
    }
}
