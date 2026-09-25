package com.fooddelivery.menu;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MenuOwnerIntegrationTest extends IntegrationTestBase {

    String ownerA;
    String ownerB;
    Long restaurantA;
    Long restaurantB;

    @BeforeEach
    void setUp() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");
        restaurantA = createRestaurant(admin, createOwner(admin, "a@example.com"), city, "A");
        restaurantB = createRestaurant(admin, createOwner(admin, "b@example.com"), city, "B");
        ownerA = login("a@example.com", "secret123");
        ownerB = login("b@example.com", "secret123");
    }

    private String items(Long restaurantId) {
        return "/api/owner/restaurants/" + restaurantId + "/menu-items";
    }

    @Test
    void createWithDefaults() throws Exception {
        postAs(ownerA, items(restaurantA), """
                {"name":" Paneer Tikka ","category":"Starters","price":249.50}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Paneer Tikka"))
                .andExpect(jsonPath("$.price").value(249.5))
                .andExpect(jsonPath("$.veg").value(true))
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.stock").doesNotExist())   // null = unlimited
                .andExpect(jsonPath("$.orderable").value(true));
    }

    @Test
    void invalidPricesAreRejected() throws Exception {
        for (String price : new String[]{"0", "-5", "12.345"}) {
            postAs(ownerA, items(restaurantA), """
                    {"name":"X","price":%s}
                    """.formatted(price))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("price"));
        }
    }

    @Test
    void updateChangesOnlyGivenFields() throws Exception {
        Long item = createMenuItem(ownerA, restaurantA, "Dal", "150", 10);

        patchAs(ownerA, items(restaurantA) + "/" + item, """
                {"price":175,"available":false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dal"))
                .andExpect(jsonPath("$.price").value(175))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.stock").value(10))
                .andExpect(jsonPath("$.orderable").value(false));
    }

    @Test
    void setStockAndUnlimited() throws Exception {
        Long item = createMenuItem(ownerA, restaurantA, "Biryani", "300", null);

        mvc.perform(put(items(restaurantA) + "/" + item + "/stock")
                        .header("Authorization", bearer(ownerA)).contentType("application/json")
                        .content("{\"stock\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(0))
                .andExpect(jsonPath("$.orderable").value(false));

        mvc.perform(put(items(restaurantA) + "/" + item + "/stock")
                        .header("Authorization", bearer(ownerA)).contentType("application/json")
                        .content("{\"stock\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").doesNotExist())
                .andExpect(jsonPath("$.orderable").value(true));
    }

    @Test
    void stockMustBeNonNegativeAndPresent() throws Exception {
        Long item = createMenuItem(ownerA, restaurantA, "Biryani", "300", 5);

        mvc.perform(put(items(restaurantA) + "/" + item + "/stock")
                        .header("Authorization", bearer(ownerA)).contentType("application/json")
                        .content("{\"stock\":-1}"))
                .andExpect(status().isBadRequest());

        // {} must not silently mean "unlimited"
        mvc.perform(put(items(restaurantA) + "/" + item + "/stock")
                        .header("Authorization", bearer(ownerA)).contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteIsSoftAndHidesItem() throws Exception {
        Long item = createMenuItem(ownerA, restaurantA, "Old Dish", "100", null);

        mvc.perform(delete(items(restaurantA) + "/" + item).header("Authorization", bearer(ownerA)))
                .andExpect(status().isNoContent());

        getAs(ownerA, items(restaurantA)).andExpect(jsonPath("$", hasSize(0)));
        assertThat(jdbc.queryForObject("SELECT active FROM menu_items WHERE id = ?", Boolean.class, item)).isFalse();
    }

    @Test
    void otherOwnerCannotSeeOrEditMenu() throws Exception {
        Long item = createMenuItem(ownerA, restaurantA, "Dal", "150", null);

        getAs(ownerB, items(restaurantA)).andExpect(status().isNotFound());
        patchAs(ownerB, items(restaurantA) + "/" + item, """
                {"price":1}
                """).andExpect(status().isNotFound());
    }

    /** IDOR: B passes THEIR OWN restaurant id with A's item id. */
    @Test
    void ownRestaurantIdWithForeignItemIdIs404() throws Exception {
        Long itemOfA = createMenuItem(ownerA, restaurantA, "Dal", "150", null);

        patchAs(ownerB, items(restaurantB) + "/" + itemOfA, """
                {"price":1}
                """)
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT price FROM menu_items WHERE id = ?", Double.class, itemOfA))
                .isEqualTo(150.0);
    }
}
