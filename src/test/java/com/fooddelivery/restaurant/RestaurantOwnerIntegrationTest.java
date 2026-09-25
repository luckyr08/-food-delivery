package com.fooddelivery.restaurant;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestaurantOwnerIntegrationTest extends IntegrationTestBase {

    String admin;
    Long city;
    Long restaurantA;
    String ownerA;
    String ownerB;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        city = createCity(admin, "Pune");
        restaurantA = createRestaurant(admin, createOwner(admin, "a@example.com"), city, "A's Kitchen");
        createRestaurant(admin, createOwner(admin, "b@example.com"), city, "B's Kitchen");
        ownerA = login("a@example.com", "secret123");
        ownerB = login("b@example.com", "secret123");
    }

    @Test
    void ownerSeesOnlyOwnRestaurants() throws Exception {
        getAs(ownerA, "/api/owner/restaurants")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("A's Kitchen"));
    }

    @Test
    void ownerOpensAndClosesRestaurant() throws Exception {
        patchAs(ownerA, "/api/owner/restaurants/" + restaurantA + "/status", """
                {"open":true}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").value(true));

        patchAs(ownerA, "/api/owner/restaurants/" + restaurantA + "/status", """
                {"open":false}
                """)
                .andExpect(jsonPath("$.open").value(false));
    }

    @Test
    void otherOwnerGets404NotForbidden() throws Exception {
        patchAs(ownerB, "/api/owner/restaurants/" + restaurantA + "/status", """
                {"open":true}
                """)
                .andExpect(status().isNotFound());
    }

    @Test
    void deactivatedRestaurantCannotBeOpened() throws Exception {
        patchAs(admin, "/api/admin/restaurants/" + restaurantA, """
                {"active":false}
                """);

        patchAs(ownerA, "/api/owner/restaurants/" + restaurantA + "/status", """
                {"open":true}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESTAURANT_INACTIVE"));
    }

    @Test
    void customerCannotUseOwnerApi() throws Exception {
        registerCustomer("c@example.com", "secret123");

        getAs(login("c@example.com", "secret123"), "/api/owner/restaurants")
                .andExpect(status().isForbidden());
    }
}
