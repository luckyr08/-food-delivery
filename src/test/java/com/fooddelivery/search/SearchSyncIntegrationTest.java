package com.fooddelivery.search;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MySQL -> outbox -> relay -> search index, driven explicitly with outboxRelay.drain(). */
class SearchSyncIntegrationTest extends IntegrationTestBase {

    String admin;
    String owner;
    Long city;
    Long restaurant;
    Long paneer;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        city = createCity(admin, "Pune");
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), city, "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        createMenuItem(owner, restaurant, "Chicken Biryani", "329.00", 5);
        paneer = createMenuItem(owner, restaurant, "Paneer Tikka", "249.00", null);
        outboxRelay.drain();
    }

    private int pendingEvents() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE processed_at IS NULL", Integer.class);
    }

    private SearchHits search(String text) {
        return searchIndex.search(new SearchQuery(city, text, null, false, false, 0, 20));
    }

    private String menuItem(Long itemId) {
        return "/api/owner/restaurants/" + restaurant + "/menu-items/" + itemId;
    }

    @Test
    void writesFlowThroughTheOutboxIntoSearch() {
        SearchHit hit = search("biryni").hits().get(0); // typo on purpose

        assertThat(hit.document().name()).isEqualTo("Spice Hub");
        assertThat(hit.matchedDishes()).containsExactly("Chicken Biryani");
        assertThat(hit.document().version()).isEqualTo(
                jdbc.queryForObject("SELECT search_version FROM restaurants WHERE id = ?", Long.class, restaurant));
        assertThat(pendingEvents()).isZero();
    }

    @Test
    void everyIndexedWriteEnqueuesOneEventButStockAndOrdersDoNot() throws Exception {
        patchAs(owner, menuItem(paneer), "{\"price\":259}").andExpect(status().isOk());
        assertThat(pendingEvents()).isEqualTo(1);

        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/status", "{\"open\":false}");
        assertThat(pendingEvents()).isEqualTo(2);

        patchAs(admin, "/api/admin/restaurants/" + restaurant, "{\"cuisine\":\"Mughlai\"}");
        assertThat(pendingEvents()).isEqualTo(3);

        // not indexed: stock changes and order placement
        mvc.perform(put(menuItem(paneer) + "/stock").header("Authorization", bearer(owner))
                .contentType("application/json").content("{\"stock\":10}")).andExpect(status().isOk());
        openRestaurant(owner, restaurant); // +1 (open flag is indexed)
        registerCustomer("c@example.com", "secret123");
        postAs(login("c@example.com", "secret123"), "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, paneer)).andExpect(status().isCreated());
        assertThat(pendingEvents()).isEqualTo(4);

        outboxRelay.drain();
        assertThat(search("paneer").hits().get(0).document().cuisine()).isEqualTo("Mughlai");
    }

    @Test
    void deactivationRemovesFromSearch() throws Exception {
        patchAs(admin, "/api/admin/restaurants/" + restaurant, "{\"active\":false}");
        outboxRelay.drain();
        assertThat(search("spice").total()).isZero();

        patchAs(admin, "/api/admin/restaurants/" + restaurant, "{\"active\":true}");
        outboxRelay.drain();
        assertThat(search("spice").total()).isEqualTo(1);

        patchAs(admin, "/api/admin/cities/" + city, "{\"active\":false}"); // city-wide event
        outboxRelay.drain();
        assertThat(search("spice").total()).isZero();
    }

    @Test
    void outageLosesNoUpdate() throws Exception {
        searchIndex.setAvailable(false);
        patchAs(owner, menuItem(paneer), "{\"name\":\"Paneer Butter Masala\"}").andExpect(status().isOk());

        outboxRelay.drain();
        var row = jdbc.queryForMap("SELECT attempts, last_error, processed_at FROM outbox_event "
                + "WHERE processed_at IS NULL");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).contains("unavailable");

        // meanwhile search still answers, from MySQL
        mvc.perform(get("/api/search/restaurants").param("cityId", city.toString()).param("q", "spice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("database-fallback"))
                .andExpect(jsonPath("$.content[0].name").value("Spice Hub"));

        // cluster back; pretend the backoff delay has passed
        searchIndex.setAvailable(true);
        jdbc.update("UPDATE outbox_event SET next_attempt_at = UTC_TIMESTAMP(6) WHERE processed_at IS NULL");
        outboxRelay.drain();

        assertThat(pendingEvents()).isZero();
        assertThat(search("butter masala").hits().get(0).matchedDishes()).containsExactly("Paneer Butter Masala");
    }

    @Test
    void manyEditsOfOneRestaurantAreCoalescedIntoOneWrite() throws Exception {
        long writesBefore = searchIndex.documentWrites();
        for (int price = 200; price < 220; price++) {
            patchAs(owner, menuItem(paneer), "{\"price\":" + price + "}").andExpect(status().isOk());
        }
        assertThat(pendingEvents()).isEqualTo(20);

        outboxRelay.drain();

        assertThat(searchIndex.documentWrites() - writesBefore).isEqualTo(1);
        assertThat(search("paneer").hits().get(0).document().menu())
                .filteredOn(m -> m.name().equals("Paneer Tikka"))
                .singleElement().satisfies(m -> assertThat(m.price()).isEqualByComparingTo("219"));
    }

    @Test
    void publicSearchAndSuggestApi() throws Exception {
        mvc.perform(get("/api/search/restaurants").param("cityId", city.toString()).param("q", "panner"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("search-index"))
                .andExpect(jsonPath("$.content[0].name").value("Spice Hub"))
                .andExpect(jsonPath("$.content[0].matchedDishes", contains("Paneer Tikka")));

        mvc.perform(get("/api/search/suggest").param("cityId", city.toString()).param("q", "chi"))
                .andExpect(jsonPath("$", contains("Chicken Biryani")));
    }

    @Test
    void adminReindexStatusAndSimulatedOutage() throws Exception {
        searchIndex.reset(); // drift: index lost everything
        postAs(admin, "/api/admin/search/reindex", "").andExpect(jsonPath("$.indexed").value(1));
        assertThat(search("spice").total()).isEqualTo(1);

        mvc.perform(put("/api/admin/search/simulated-availability").header("Authorization", bearer(admin))
                        .contentType("application/json").content("{\"available\":false}"))
                .andExpect(jsonPath("$.indexAvailable").value(false));
        getAs(admin, "/api/admin/search/status")
                .andExpect(jsonPath("$.documents").value(1))
                .andExpect(jsonPath("$.pending").value(0));

        registerCustomer("c@example.com", "secret123");
        postAs(login("c@example.com", "secret123"), "/api/admin/search/reindex", "")
                .andExpect(status().isForbidden());
    }
}
