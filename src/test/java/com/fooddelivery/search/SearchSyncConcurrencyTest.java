package com.fooddelivery.search;

import com.fooddelivery.support.ConcurrencyTestBase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSyncConcurrencyTest extends ConcurrencyTestBase {

    /**
     * Menu edits (bump restaurants.search_version, then update the item) race order placements
     * (orders FK S-locks the restaurant, then the stock UPDATE X-locks the item) on the same restaurant.
     * The bump-first order makes a lock cycle impossible; the index must converge to the DB state.
     */
    @Test
    void menuEditsRacingOrdersNeitherDeadlockNorDiverge() throws Exception {
        Long item = insertMenuItem("Biryani", 1000);
        List<String> customers = customers(20);

        List<Integer> statuses = runConcurrently(40, i -> i % 2 == 0
                ? send("PATCH", "/api/owner/restaurants/" + restaurantId + "/menu-items/" + item, ownerToken,
                "{\"price\":" + (300 + i) + "}").statusCode()
                : placeOrder(customers.get(i / 2), "[{\"menuItemId\":" + item + ",\"quantity\":1}]", null).statusCode());

        // Orders always succeed. An edit may get 409: each sale bumps the item's @Version (ADR 0007/0008),
        // so an owner edit loaded just before a sale conflicts instead of overwriting it. Never 500.
        for (int i = 0; i < statuses.size(); i++) {
            assertThat(statuses.get(i)).as("request %d", i).isIn(i % 2 == 0 ? List.of(200, 409) : List.of(201));
        }
        outboxRelay.drain();
        RestaurantDocument doc = searchIndex.search(new SearchQuery(cityId, "biryani", null, false, false, 0, 1))
                .hits().get(0).document();
        assertThat(doc.version()).isEqualTo(jdbc.queryForObject(
                "SELECT search_version FROM restaurants WHERE id = ?", Long.class, restaurantId));
        assertThat(doc.menu().get(0).price()).isEqualByComparingTo(jdbc.queryForObject(
                "SELECT price FROM menu_items WHERE id = ?", java.math.BigDecimal.class, item));
    }

    /** SKIP LOCKED: parallel relays (think: several app instances) take disjoint batches. */
    @Test
    void parallelRelaysClaimEveryEventExactlyOnce() throws Exception {
        insertMenuItem("Dal", 10);
        for (int i = 0; i < 300; i++) {
            jdbc.update("INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, created_at, "
                    + "next_attempt_at) VALUES ('RESTAURANT', ?, 'RESTAURANT_CHANGED', UTC_TIMESTAMP(6), "
                    + "UTC_TIMESTAMP(6))", restaurantId);
        }

        List<Integer> claimed = runConcurrently(4, i -> outboxRelay.drain());

        assertThat(claimed.stream().mapToInt(Integer::intValue).sum()).isEqualTo(300); // no row claimed twice
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE processed_at IS NULL", Integer.class))
                .isZero();
    }
}
