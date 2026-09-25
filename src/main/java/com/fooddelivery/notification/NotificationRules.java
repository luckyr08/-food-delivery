package com.fooddelivery.notification;

import com.fooddelivery.order.OrderEvent;
import com.fooddelivery.order.OrderStatus;
import com.fooddelivery.user.Role;

import java.util.ArrayList;
import java.util.List;

/** Pure rules: who hears about an event, and what they are told. */
public final class NotificationRules {

    private NotificationRules() {
    }

    /** Everyone involved in the order except whoever caused the event (their HTTP response already told them). */
    public static List<Recipient> recipients(OrderEvent e) {
        List<Recipient> all = new ArrayList<>();
        all.add(new Recipient(e.customerId(), Role.CUSTOMER));
        all.add(new Recipient(e.restaurantOwnerId(), Role.RESTAURANT_OWNER));
        if (e.partnerUserId() != null) {
            all.add(new Recipient(e.partnerUserId(), Role.DELIVERY_PARTNER));
        }
        return all.stream()
                .filter(r -> !r.userId().equals(e.actorUserId()))
                .toList();
    }

    public static NotificationType type(OrderEvent e) {
        return e.kind() == OrderEvent.Kind.PARTNER_ASSIGNED
                ? NotificationType.PARTNER_ASSIGNED
                : NotificationType.ORDER_STATUS_CHANGED;
    }

    public static String message(OrderEvent e, Recipient r) {
        String order = "Order #" + e.orderId();
        if (e.kind() == OrderEvent.Kind.PARTNER_ASSIGNED) {
            return "A delivery partner has been assigned to " + order + " from " + e.restaurantName();
        }
        if (e.toStatus() == OrderStatus.PLACED && r.role() == Role.RESTAURANT_OWNER) {
            return "New " + order + " received";
        }
        String text = order + " from " + e.restaurantName() + " is now " + e.toStatus();
        return e.reason() == null ? text : text + " (" + e.reason() + ")";
    }
}
