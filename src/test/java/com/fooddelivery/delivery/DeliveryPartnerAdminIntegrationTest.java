package com.fooddelivery.delivery;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DeliveryPartnerAdminIntegrationTest extends IntegrationTestBase {

    private static final String PARTNER_JSON = """
            {"name":"Kiran","email":"kiran@example.com","password":"secret123","phone":"9876543210",
             "cityId":%d,"vehicleType":"SCOOTER"}
            """;

    @Test
    void createsUserAndProfileTogether() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");

        postAs(admin, "/api/admin/delivery-partners", PARTNER_JSON.formatted(city))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OFFLINE"))
                .andExpect(jsonPath("$.vehicleType").value("SCOOTER"))
                .andExpect(jsonPath("$.cityName").value("Pune"))
                .andExpect(jsonPath("$.email").value("kiran@example.com"));

        String role = jdbc.queryForObject("SELECT role FROM users WHERE email='kiran@example.com'", String.class);
        assertThat(role).isEqualTo("DELIVERY_PARTNER");
        login("kiran@example.com", "secret123");
    }

    @Test
    void failureLeavesNoPartialData() throws Exception {
        postAs(adminToken(), "/api/admin/delivery-partners", PARTNER_JSON.formatted(999999))
                .andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='kiran@example.com'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM delivery_partners", Integer.class)).isZero();
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");
        postAs(admin, "/api/admin/delivery-partners", PARTNER_JSON.formatted(city)).andExpect(status().isCreated());

        postAs(admin, "/api/admin/delivery-partners", PARTNER_JSON.formatted(city))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void invalidVehicleTypeIs400() throws Exception {
        String admin = adminToken();
        Long city = createCity(admin, "Pune");

        postAs(admin, "/api/admin/delivery-partners", PARTNER_JSON.formatted(city).replace("SCOOTER", "ROCKET"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateCityAndFilterByCity() throws Exception {
        String admin = adminToken();
        Long pune = createCity(admin, "Pune");
        Long goa = createCity(admin, "Goa");
        Long id = idFrom(postAs(admin, "/api/admin/delivery-partners", PARTNER_JSON.formatted(pune)), "$.id");

        patchAs(admin, "/api/admin/delivery-partners/" + id, """
                {"cityId":%d,"vehicleType":"BICYCLE"}
                """.formatted(goa))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cityName").value("Goa"))
                .andExpect(jsonPath("$.vehicleType").value("BICYCLE"));

        getAs(admin, "/api/admin/delivery-partners?cityId=" + goa + "&status=OFFLINE")
                .andExpect(jsonPath("$.totalElements").value(1));
        getAs(admin, "/api/admin/delivery-partners?cityId=" + pune)
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
