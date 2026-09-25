package com.fooddelivery.restaurant;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestaurantAdminIntegrationTest extends IntegrationTestBase {

    @Test
    void onboardOwnerThenRestaurant() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");

        Long owner = idFrom(postAs(admin, "/api/admin/restaurant-owners", """
                {"name":"Ravi","email":"ravi@example.com","password":"secret123"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("RESTAURANT_OWNER")), "$.id");

        postAs(admin, "/api/admin/restaurants", """
                {"ownerId":%d,"cityId":%d,"name":"Spice Hub","address":"MG Road","cuisine":"North Indian"}
                """.formatted(owner, city))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.open").value(false))  // starts closed
                .andExpect(jsonPath("$.ownerName").value("Ravi"))
                .andExpect(jsonPath("$.cityName").value("Pune"))
                .andExpect(jsonPath("$.averageRating").doesNotExist());

        // the owner account works
        login("ravi@example.com", "secret123");
    }

    @Test
    void ownerMustHaveOwnerRole() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");
        registerCustomer("cust@example.com", "secret123");
        Long customerId = jdbc.queryForObject("SELECT id FROM users WHERE email='cust@example.com'", Long.class);

        postAs(admin, "/api/admin/restaurants", """
                {"ownerId":%d,"cityId":%d,"name":"X","address":"Y"}
                """.formatted(customerId, city))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_OWNER"));
    }

    @Test
    void cityMustBeActive() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");
        Long owner = createOwner(admin, "o@example.com");
        patchAs(admin, "/api/admin/cities/" + city, """
                {"active":false}
                """);

        postAs(admin, "/api/admin/restaurants", """
                {"ownerId":%d,"cityId":%d,"name":"X","address":"Y"}
                """.formatted(owner, city))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CITY_INACTIVE"));
    }

    @Test
    void unknownCityIs404() throws Exception {
        String admin = adminToken();
        Long owner = createOwner(admin, "o@example.com");

        postAs(admin, "/api/admin/restaurants", """
                {"ownerId":%d,"cityId":999999,"name":"X","address":"Y"}
                """.formatted(owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void oneOwnerCanHaveSeveralRestaurants() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");
        Long owner = createOwner(admin, "o@example.com");
        createRestaurant(admin, owner, city, "Branch A");
        createRestaurant(admin, owner, city, "Branch B");

        getAs(admin, "/api/admin/restaurants")
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void searchFiltersByCityAndActiveAndPaginates() throws Exception {
        String admin = adminToken();
        Long pune = createCity(admin, "Pune");
        Long goa = createCity(admin, "Goa");
        Long owner = createOwner(admin, "o@example.com");
        createRestaurant(admin, owner, pune, "A");
        createRestaurant(admin, owner, pune, "B");
        Long c = createRestaurant(admin, owner, pune, "C");
        createRestaurant(admin, owner, goa, "D");
        patchAs(admin, "/api/admin/restaurants/" + c, """
                {"active":false}
                """).andExpect(jsonPath("$.active").value(false));

        getAs(admin, "/api/admin/restaurants?cityId=" + pune + "&active=true&size=1&page=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].name").value("B")) // sorted by name
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void pageSizeAbove100IsRejected() throws Exception {
        getAs(adminToken(), "/api/admin/restaurants?size=500")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void deactivatingRestaurantAlsoClosesIt() throws Exception {
        String admin = adminToken();
        Long id = createRestaurant(admin, createOwner(admin, "o@example.com"), createCity(admin, "Pune"), "A");
        jdbc.update("UPDATE restaurants SET is_open = TRUE WHERE id = ?", id);

        patchAs(admin, "/api/admin/restaurants/" + id, """
                {"active":false}
                """)
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.open").value(false));
    }
}
