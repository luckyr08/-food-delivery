package com.fooddelivery.notification;

import com.fooddelivery.order.OrderEvent;
import com.fooddelivery.order.OrderStatus;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationRulesTest {

    private static final long CUSTOMER = 1, OWNER = 2, PARTNER = 3;

    private static OrderEvent event(OrderEvent.Kind kind, OrderStatus to, Long partner, long actor, String reason) {
        return new OrderEvent(kind, 42L, OrderStatus.PLACED, to, CUSTOMER, OWNER, partner, "Spice Hub",
                actor, reason, Instant.now());
    }

    @Test
    void placedOrderNotifiesOnlyTheRestaurant() {
        var e = event(OrderEvent.Kind.STATUS_CHANGED, OrderStatus.PLACED, null, CUSTOMER, null);

        assertThat(NotificationRules.recipients(e)).containsExactly(new Recipient(OWNER, Role.RESTAURANT_OWNER));
        assertThat(NotificationRules.message(e, new Recipient(OWNER, Role.RESTAURANT_OWNER)))
                .isEqualTo("New Order #42 received");
    }

    @Test
    void ownerActionNotifiesCustomerAndAssignedPartnerNotTheOwner() {
        var e = event(OrderEvent.Kind.STATUS_CHANGED, OrderStatus.READY_FOR_PICKUP, PARTNER, OWNER, null);

        assertThat(NotificationRules.recipients(e)).extracting(Recipient::userId).containsExactly(CUSTOMER, PARTNER);
    }

    @Test
    void partnerAssignedGoesToCustomerAndOwner() {
        var e = event(OrderEvent.Kind.PARTNER_ASSIGNED, OrderStatus.ACCEPTED, PARTNER, PARTNER, null);

        assertThat(NotificationRules.recipients(e)).extracting(Recipient::userId).containsExactly(CUSTOMER, OWNER);
        assertThat(NotificationRules.type(e)).isEqualTo(NotificationType.PARTNER_ASSIGNED);
    }

    @Test
    void reasonIsIncludedInTheMessage() {
        var e = event(OrderEvent.Kind.STATUS_CHANGED, OrderStatus.REJECTED, null, OWNER, "Kitchen closed");

        assertThat(NotificationRules.message(e, new Recipient(CUSTOMER, Role.CUSTOMER)))
                .isEqualTo("Order #42 from Spice Hub is now REJECTED (Kitchen closed)");
    }
}
