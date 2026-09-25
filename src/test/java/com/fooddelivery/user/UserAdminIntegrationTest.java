package com.fooddelivery.user;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class UserAdminIntegrationTest extends IntegrationTestBase {

    @Test
    void blockedUserCannotLogInAndUnblockedCan() throws Exception {
        String admin = adminToken();
        registerCustomer("c@example.com", "secret123");
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email='c@example.com'", Long.class);

        patchAs(admin, "/api/admin/users/" + id + "/status", """
                {"active":false}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("""
                        {"email":"c@example.com","password":"secret123"}
                        """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

        patchAs(admin, "/api/admin/users/" + id + "/status", """
                {"active":true}
                """).andExpect(status().isOk());
        login("c@example.com", "secret123");
    }

    @Test
    void adminCannotDeactivateThemselves() throws Exception {
        Long adminId = jdbc.queryForObject("SELECT id FROM users WHERE email=?", Long.class, ADMIN_EMAIL);

        patchAs(adminToken(), "/api/admin/users/" + adminId + "/status", """
                {"active":false}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_DEACTIVATE_SELF"));
    }

    @Test
    void missingActiveFieldIsValidationError() throws Exception {
        patchAs(adminToken(), "/api/admin/users/1/status", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
