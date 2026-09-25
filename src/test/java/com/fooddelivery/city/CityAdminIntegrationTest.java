package com.fooddelivery.city;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CityAdminIntegrationTest extends IntegrationTestBase {

    @Test
    void adminCreatesCity() throws Exception {
        postAs(adminToken(), "/api/admin/cities", """
                {"name":"  Pune ","state":"Maharashtra"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pune"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void duplicateNameIsRejectedIgnoringCase() throws Exception {
        String admin = adminToken();
        createCity(admin, "Pune");

        postAs(admin, "/api/admin/cities", """
                {"name":"pUNE"}
                """)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CITY_ALREADY_EXISTS"));
    }

    @Test
    void blankNameIsValidationError() throws Exception {
        postAs(adminToken(), "/api/admin/cities", """
                {"name":"   "}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void customerCannotCreateCity() throws Exception {
        registerCustomer("c@example.com", "secret123");

        postAs(login("c@example.com", "secret123"), "/api/admin/cities", """
                {"name":"Goa"}
                """)
                .andExpect(status().isForbidden());
    }

    @Test
    void renameToExistingNameIsConflict() throws Exception {
        String admin = adminToken();
        createCity(admin, "Pune");
        Long mumbai = createCity(admin, "Mumbai");

        patchAs(admin, "/api/admin/cities/" + mumbai, """
                {"name":"PUNE"}
                """)
                .andExpect(status().isConflict());
    }

    @Test
    void publicListShowsOnlyActiveCitiesWhileAdminSeesAll() throws Exception {
        String admin = adminToken();
        createCity(admin, "Pune");
        Long goa = createCity(admin, "Goa");
        patchAs(admin, "/api/admin/cities/" + goa, """
                {"active":false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(get("/api/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Pune"));

        getAs(admin, "/api/admin/cities")
                .andExpect(jsonPath("$", hasSize(2)));
    }

    @Test
    void updatingUnknownCityIs404() throws Exception {
        patchAs(adminToken(), "/api/admin/cities/999999", """
                {"name":"X"}
                """)
                .andExpect(status().isNotFound());
    }
}
