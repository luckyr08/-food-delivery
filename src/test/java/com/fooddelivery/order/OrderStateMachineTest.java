package com.fooddelivery.order;

import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ForbiddenException;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static com.fooddelivery.order.OrderStatus.*;
import static com.fooddelivery.user.Role.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exhaustive: every (from, to, role) combination is checked against the expected table below,
 * so any accidental change to the state machine fails this test.
 */
class OrderStateMachineTest {

    private static final Map<String, Set<Role>> EXPECTED = Map.ofEntries(
            Map.entry("PLACED>ACCEPTED", Set.of(RESTAURANT_OWNER)),
            Map.entry("PLACED>REJECTED", Set.of(RESTAURANT_OWNER)),
            Map.entry("PLACED>CANCELLED", Set.of(CUSTOMER, ADMIN)),
            Map.entry("ACCEPTED>PREPARING", Set.of(RESTAURANT_OWNER)),
            Map.entry("ACCEPTED>CANCELLED", Set.of(CUSTOMER, ADMIN)),
            Map.entry("PREPARING>READY_FOR_PICKUP", Set.of(RESTAURANT_OWNER)),
            Map.entry("PREPARING>CANCELLED", Set.of(ADMIN)),
            Map.entry("READY_FOR_PICKUP>OUT_FOR_DELIVERY", Set.of(DELIVERY_PARTNER)),
            Map.entry("READY_FOR_PICKUP>CANCELLED", Set.of(ADMIN)),
            Map.entry("OUT_FOR_DELIVERY>DELIVERED", Set.of(DELIVERY_PARTNER)),
            Map.entry("OUT_FOR_DELIVERY>CANCELLED", Set.of(ADMIN)));

    @Test
    void everyCombinationMatchesTheTable() {
        int checked = 0;
        for (OrderStatus from : OrderStatus.values()) {
            for (OrderStatus to : OrderStatus.values()) {
                Set<Role> allowed = EXPECTED.get(from + ">" + to);
                for (Role role : Role.values()) {
                    checked++;
                    if (allowed == null) {
                        assertThatThrownBy(() -> OrderStateMachine.check(from, to, role))
                                .as("%s -> %s by %s", from, to, role)
                                .isInstanceOf(ConflictException.class);
                    } else if (allowed.contains(role)) {
                        assertThatCode(() -> OrderStateMachine.check(from, to, role))
                                .as("%s -> %s by %s", from, to, role)
                                .doesNotThrowAnyException();
                    } else {
                        assertThatThrownBy(() -> OrderStateMachine.check(from, to, role))
                                .as("%s -> %s by %s", from, to, role)
                                .isInstanceOf(ForbiddenException.class);
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(8 * 8 * 4);
    }

    @Test
    void finalStatesHaveNoWayOut() {
        assertThat(OrderStateMachine.isFinal(DELIVERED)).isTrue();
        assertThat(OrderStateMachine.isFinal(REJECTED)).isTrue();
        assertThat(OrderStateMachine.isFinal(CANCELLED)).isTrue();
        assertThat(OrderStateMachine.isFinal(PLACED)).isFalse();
    }

    @Test
    void onlyUncookedOrdersAreRestocked() {
        assertThat(OrderStateMachine.restocksOnCancel(PLACED)).isTrue();
        assertThat(OrderStateMachine.restocksOnCancel(ACCEPTED)).isTrue();
        assertThat(OrderStateMachine.restocksOnCancel(PREPARING)).isFalse();
        assertThat(OrderStateMachine.restocksOnCancel(OUT_FOR_DELIVERY)).isFalse();
    }
}
