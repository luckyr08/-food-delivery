package com.fooddelivery.order;

import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.ForbiddenException;
import com.fooddelivery.user.Role;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.fooddelivery.order.OrderStatus.ACCEPTED;
import static com.fooddelivery.order.OrderStatus.CANCELLED;
import static com.fooddelivery.order.OrderStatus.DELIVERED;
import static com.fooddelivery.order.OrderStatus.OUT_FOR_DELIVERY;
import static com.fooddelivery.order.OrderStatus.PAYMENT_PENDING;
import static com.fooddelivery.order.OrderStatus.PLACED;
import static com.fooddelivery.order.OrderStatus.PREPARING;
import static com.fooddelivery.order.OrderStatus.READY_FOR_PICKUP;
import static com.fooddelivery.order.OrderStatus.REJECTED;
import static com.fooddelivery.user.Role.ADMIN;
import static com.fooddelivery.user.Role.CUSTOMER;
import static com.fooddelivery.user.Role.DELIVERY_PARTNER;
import static com.fooddelivery.user.Role.RESTAURANT_OWNER;
import static com.fooddelivery.user.Role.SYSTEM;

/**
 * The single source of truth for the order lifecycle: which transitions exist and which role may
 * perform each. Pure logic (no Spring, no DB) so it can be tested exhaustively.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Map<OrderStatus, Set<Role>>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        // Payment saga: only the system resolves a pending payment (confirmed, declined, expired).
        allow(PAYMENT_PENDING, PLACED, SYSTEM);
        allow(PAYMENT_PENDING, CANCELLED, SYSTEM);

        allow(PLACED, ACCEPTED, RESTAURANT_OWNER);
        allow(PLACED, REJECTED, RESTAURANT_OWNER);
        allow(PLACED, CANCELLED, CUSTOMER, ADMIN);

        allow(ACCEPTED, PREPARING, RESTAURANT_OWNER);
        allow(ACCEPTED, CANCELLED, CUSTOMER, ADMIN);

        // From here the food is being cooked: customers can no longer cancel, only admins (support).
        allow(PREPARING, READY_FOR_PICKUP, RESTAURANT_OWNER);
        allow(PREPARING, CANCELLED, ADMIN);

        allow(READY_FOR_PICKUP, OUT_FOR_DELIVERY, DELIVERY_PARTNER);
        allow(READY_FOR_PICKUP, CANCELLED, ADMIN);

        allow(OUT_FOR_DELIVERY, DELIVERED, DELIVERY_PARTNER);
        allow(OUT_FOR_DELIVERY, CANCELLED, ADMIN);
        // DELIVERED, REJECTED, CANCELLED are final: no outgoing transitions.
    }

    private OrderStateMachine() {
    }

    private static void allow(OrderStatus from, OrderStatus to, Role... roles) {
        TRANSITIONS.computeIfAbsent(from, k -> new EnumMap<>(OrderStatus.class))
                .put(to, EnumSet.of(roles[0], roles));
    }

    /**
     * @throws ConflictException  409 if the transition doesn't exist from the current state
     * @throws ForbiddenException 403 if it exists but this role may not perform it
     */
    public static void check(OrderStatus from, OrderStatus to, Role role) {
        Set<Role> roles = TRANSITIONS.getOrDefault(from, Map.of()).get(to);
        if (roles == null) {
            throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "Order cannot move from " + from + " to " + to);
        }
        if (!roles.contains(role)) {
            throw new ForbiddenException(ErrorCode.TRANSITION_NOT_ALLOWED_FOR_ROLE,
                    role + " cannot move an order from " + from + " to " + to);
        }
    }

    public static boolean isFinal(OrderStatus status) {
        return !TRANSITIONS.containsKey(status);
    }

    /**
     * Stock goes back only if the food was never cooked. After PREPARING the ingredients are used,
     * so an admin cancellation refunds the customer but doesn't restock.
     */
    public static boolean restocksOnCancel(OrderStatus from) {
        return from == PAYMENT_PENDING || from == PLACED || from == ACCEPTED;
    }
}
