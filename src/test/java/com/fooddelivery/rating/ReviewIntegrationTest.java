package com.fooddelivery.rating;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReviewIntegrationTest extends IntegrationTestBase {

    String admin;
    String owner;
    String customer;
    String partner;
    Long restaurant;
    Long dal;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        Long city = createCity(admin, "Pune");
        restaurant = createRestaurant(admin, createOwner(admin, "owner@example.com"), city, "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        dal = createMenuItem(owner, restaurant, "Dal", "150.00", null);
        mvc.perform(post("/api/auth/register").contentType("application/json").content("""
                {"name":"Asha Kulkarni","email":"asha@example.com","password":"secret123"}
                """)).andExpect(status().isCreated());
        customer = login("asha@example.com", "secret123");
        postAs(admin, "/api/admin/delivery-partners", """
                {"name":"Kiran","email":"kiran@example.com","password":"secret123","cityId":%d,"vehicleType":"SCOOTER"}
                """.formatted(city)).andExpect(status().isCreated());
        partner = login("kiran@example.com", "secret123");
        patchAs(partner, "/api/partner/me/status", "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk());
    }

    private Long placeOrder() throws Exception {
        return idFrom(postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, dal)).andExpect(status().isCreated()), "$.id");
    }

    /** Drives an order through the real lifecycle to DELIVERED. */
    private Long deliveredOrder() throws Exception {
        Long order = placeOrder();
        String ownerPath = "/api/owner/restaurants/" + restaurant + "/orders/" + order + "/status";
        patchAs(owner, ownerPath, "{\"status\":\"ACCEPTED\"}").andExpect(status().isOk());
        postAs(partner, "/api/partner/orders/" + order + "/claim", "").andExpect(status().isOk());
        patchAs(owner, ownerPath, "{\"status\":\"PREPARING\"}").andExpect(status().isOk());
        patchAs(owner, ownerPath, "{\"status\":\"READY_FOR_PICKUP\"}").andExpect(status().isOk());
        patchAs(partner, "/api/partner/orders/" + order + "/status", "{\"status\":\"OUT_FOR_DELIVERY\"}")
                .andExpect(status().isOk());
        patchAs(partner, "/api/partner/orders/" + order + "/status", "{\"status\":\"DELIVERED\"}")
                .andExpect(status().isOk());
        return order;
    }

    private String review(int restaurantRating, Integer partnerRating, String comment) {
        return """
                {"restaurantRating":%d,"partnerRating":%s,"comment":%s}
                """.formatted(restaurantRating, partnerRating, comment == null ? "null" : "\"" + comment + "\"");
    }

    @Test
    void reviewUpdatesRestaurantAndPartnerAverages() throws Exception {
        Long first = deliveredOrder();
        Long second = deliveredOrder();

        postAs(customer, "/api/orders/" + first + "/review", review(5, 4, "  Great dal! "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.comment").value("Great dal!"))
                .andExpect(jsonPath("$.partnerRating").value(4));
        postAs(customer, "/api/orders/" + second + "/review", review(4, null, null))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/restaurants/" + restaurant))
                .andExpect(jsonPath("$.averageRating").value(4.5))
                .andExpect(jsonPath("$.ratingCount").value(2));
        // the second review had no partner rating: partner average unchanged
        getAs(partner, "/api/partner/me")
                .andExpect(jsonPath("$.averageRating").value(4.0))
                .andExpect(jsonPath("$.ratingCount").value(1));
        getAs(customer, "/api/orders/" + first + "/review").andExpect(jsonPath("$.restaurantRating").value(5));
    }

    @Test
    void publicListShowsFirstNameOnlyAndNoPartnerRating() throws Exception {
        Long order = deliveredOrder();
        postAs(customer, "/api/orders/" + order + "/review", review(5, 2, "Tasty")).andExpect(status().isCreated());

        mvc.perform(get("/api/restaurants/" + restaurant + "/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].reviewer").value("Asha"))
                .andExpect(jsonPath("$.content[0].rating").value(5))
                .andExpect(jsonPath("$.content[0].partnerRating").doesNotExist())
                .andExpect(jsonPath("$.content[0].comment").value("Tasty"));
    }

    @Test
    void cannotReviewBeforeDelivery() throws Exception {
        Long order = placeOrder();

        postAs(customer, "/api/orders/" + order + "/review", review(5, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_DELIVERED"));
    }

    @Test
    void onlyOneReviewPerOrder() throws Exception {
        Long order = deliveredOrder();
        postAs(customer, "/api/orders/" + order + "/review", review(5, null, null)).andExpect(status().isCreated());

        postAs(customer, "/api/orders/" + order + "/review", review(1, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REVIEWED"));
        assertThat(jdbc.queryForObject("SELECT rating_count FROM restaurants WHERE id = ?", Integer.class, restaurant))
                .isEqualTo(1);
    }

    @Test
    void reviewWindowCloses() throws Exception {
        Long order = deliveredOrder();
        jdbc.update("UPDATE order_status_history SET changed_at = UTC_TIMESTAMP(6) - INTERVAL 8 DAY "
                + "WHERE order_id = ? AND to_status = 'DELIVERED'", order);

        postAs(customer, "/api/orders/" + order + "/review", review(5, null, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_WINDOW_CLOSED"));
    }

    @Test
    void ratingsOutsideOneToFiveAreRejected() throws Exception {
        Long order = deliveredOrder();

        postAs(customer, "/api/orders/" + order + "/review", review(6, null, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("restaurantRating"));
        postAs(customer, "/api/orders/" + order + "/review", review(4, 0, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("partnerRating"));
    }

    @Test
    void otherCustomersCannotReviewYourOrder() throws Exception {
        Long order = deliveredOrder();
        registerCustomer("eve@example.com", "secret123");

        postAs(login("eve@example.com", "secret123"), "/api/orders/" + order + "/review", review(1, null, null))
                .andExpect(status().isNotFound());
    }
}
