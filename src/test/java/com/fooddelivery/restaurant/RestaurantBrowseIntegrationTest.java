package com.fooddelivery.restaurant;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public endpoints: no token is sent in any request. */
class RestaurantBrowseIntegrationTest extends IntegrationTestBase {

    String admin;
    Long pune;
    Long owner;
    String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        pune = createCity(admin, "Pune");
        owner = createOwner(admin, "o@example.com");
        ownerToken = login("o@example.com", "secret123");
    }

    private void open(Long restaurantId) throws Exception {
        patchAs(ownerToken, "/api/owner/restaurants/" + restaurantId + "/status", """
                {"open":true}
                """).andExpect(status().isOk());
    }

    @Test
    void listsActiveRestaurantsOpenFirst() throws Exception {
        createRestaurant(admin, owner, pune, "Alpha Diner");
        Long zeta = createRestaurant(admin, owner, pune, "Zeta Cafe");
        Long hidden = createRestaurant(admin, owner, pune, "Hidden");
        open(zeta);
        patchAs(admin, "/api/admin/restaurants/" + hidden, """
                {"active":false}
                """);

        mvc.perform(get("/api/restaurants").param("cityId", pune.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].name", contains("Zeta Cafe", "Alpha Diner")))
                .andExpect(jsonPath("$.content[0].ownerId").doesNotExist()); // customer DTO

        mvc.perform(get("/api/restaurants").param("cityId", pune.toString()).param("openOnly", "true"))
                .andExpect(jsonPath("$.content[*].name", contains("Zeta Cafe")));
    }

    @Test
    void cityIdIsRequired() throws Exception {
        mvc.perform(get("/api/restaurants")).andExpect(status().isBadRequest());
    }

    @Test
    void inactiveCityShowsNothing() throws Exception {
        createRestaurant(admin, owner, pune, "Alpha");
        patchAs(admin, "/api/admin/cities/" + pune, """
                {"active":false}
                """);

        mvc.perform(get("/api/restaurants").param("cityId", pune.toString()))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void searchIsCaseInsensitiveAndEscapesWildcards() throws Exception {
        createRestaurant(admin, owner, pune, "Spice Hub");
        createRestaurant(admin, owner, pune, "100% Veg");
        createRestaurant(admin, owner, pune, "1000 Flavours");

        mvc.perform(get("/api/restaurants").param("cityId", pune.toString()).param("q", "SPICE"))
                .andExpect(jsonPath("$.content[*].name", contains("Spice Hub")));

        // "%" is literal: must not match "1000 Flavours"
        mvc.perform(get("/api/restaurants").param("cityId", pune.toString()).param("q", "100%"))
                .andExpect(jsonPath("$.content[*].name", contains("100% Veg")));
    }

    @Test
    void menuShowsActiveItemsWithAvailabilityButNoStock() throws Exception {
        Long r = createRestaurant(admin, owner, pune, "Spice Hub");
        createMenuItem(ownerToken, r, "Dal", "150", null);
        createMenuItem(ownerToken, r, "Biryani", "300", 0);   // sold out
        Long deleted = createMenuItem(ownerToken, r, "Gone", "99", null);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/owner/restaurants/" + r + "/menu-items/" + deleted)
                .header("Authorization", bearer(ownerToken)));

        mvc.perform(get("/api/restaurants/" + r + "/menu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.name=='Biryani')].available").value(false))
                .andExpect(jsonPath("$[?(@.name=='Dal')].available").value(true))
                .andExpect(jsonPath("$[0].stock").doesNotExist());
    }

    @Test
    void vegOnlyAndCategoryFilters() throws Exception {
        Long r = createRestaurant(admin, owner, pune, "Spice Hub");
        postAs(ownerToken, "/api/owner/restaurants/" + r + "/menu-items", """
                {"name":"Chicken Curry","category":"Mains","price":250,"veg":false}
                """);
        postAs(ownerToken, "/api/owner/restaurants/" + r + "/menu-items", """
                {"name":"Paneer Tikka","category":"Starters","price":200}
                """);

        mvc.perform(get("/api/restaurants/" + r + "/menu").param("vegOnly", "true"))
                .andExpect(jsonPath("$[*].name", contains("Paneer Tikka")));
        mvc.perform(get("/api/restaurants/" + r + "/menu").param("category", "Mains"))
                .andExpect(jsonPath("$[*].name", contains("Chicken Curry")));
    }

    @Test
    void inactiveRestaurantIs404() throws Exception {
        Long r = createRestaurant(admin, owner, pune, "Gone");
        patchAs(admin, "/api/admin/restaurants/" + r, """
                {"active":false}
                """);

        mvc.perform(get("/api/restaurants/" + r)).andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurants/" + r + "/menu")).andExpect(status().isNotFound());
    }
}
