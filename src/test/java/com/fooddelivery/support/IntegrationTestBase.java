package com.fooddelivery.support;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.sql.Statement;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full application + real MySQL (food_delivery_test) + MockMvc.
 * Every test starts from a clean database (only the seeded admin remains). We clean up instead of
 * rolling back because concurrency tests need real commits from several threads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String ADMIN_EMAIL = "admin@fooddelivery.com";
    protected static final String ADMIN_PASSWORD = "Admin@123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'",
                String.class);
        // FOREIGN_KEY_CHECKS is per connection, so everything must run on ONE connection
        // (separate jdbc.update calls could each get a different pooled connection).
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("SET FOREIGN_KEY_CHECKS = 0");
                for (String table : tables) {
                    st.execute(table.equals("users")
                            ? "DELETE FROM users WHERE email <> '" + ADMIN_EMAIL + "'"
                            : "DELETE FROM " + table);
                }
                st.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            return null;
        });
    }

    // ---- auth helpers ----

    protected void registerCustomer(String email, String password) throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Test Customer","email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isCreated());
    }

    protected String login(String email, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    protected String adminToken() throws Exception {
        return login(ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    // ---- request helpers ----

    protected ResultActions getAs(String token, String url) throws Exception {
        return mvc.perform(get(url).header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    protected ResultActions postAs(String token, String url, String json) throws Exception {
        return mvc.perform(post(url).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions patchAs(String token, String url, String json) throws Exception {
        return mvc.perform(patch(url).header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Reads a numeric id from a response (JsonPath returns Integer for small numbers). */
    protected static Long idFrom(ResultActions result, String path) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), path)).longValue();
    }

    // ---- fixtures, created through the real admin API ----

    protected Long createCity(String adminToken, String name) throws Exception {
        return idFrom(postAs(adminToken, "/api/admin/cities", """
                {"name":"%s","state":"Test State"}
                """.formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    protected Long createOwner(String adminToken, String email) throws Exception {
        return idFrom(postAs(adminToken, "/api/admin/restaurant-owners", """
                {"name":"Owner","email":"%s","password":"secret123"}
                """.formatted(email)).andExpect(status().isCreated()), "$.id");
    }

    protected Long createRestaurant(String adminToken, Long ownerId, Long cityId, String name) throws Exception {
        return idFrom(postAs(adminToken, "/api/admin/restaurants", """
                {"ownerId":%d,"cityId":%d,"name":"%s","address":"1 Test Road","cuisine":"Indian"}
                """.formatted(ownerId, cityId, name)).andExpect(status().isCreated()), "$.id");
    }
}
