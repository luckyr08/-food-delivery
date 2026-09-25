package com.fooddelivery.auth;

import com.fooddelivery.security.AuthUser;
import com.fooddelivery.security.JwtProperties;
import com.fooddelivery.security.JwtService;
import com.fooddelivery.support.IntegrationTestBase;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    JwtProperties jwtProperties;

    @Test
    void registerLoginAndFetchOwnProfile() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Asha","email":"  Asha@Example.com ","password":"secret123","phone":"+919876543210"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("asha@example.com")) // normalized
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String token = login("ASHA@example.com", "secret123");

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("asha@example.com"))
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void registrationCannotChooseARole() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Eve","email":"eve@example.com","password":"secret123","role":"ADMIN"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void duplicateEmailIsConflict() throws Exception {
        registerCustomer("dup@example.com", "secret123");

        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Dup","email":"DUP@example.com","password":"secret123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    void invalidRegistrationListsFieldErrors() throws Exception {
        mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3));
    }

    @Test
    void wrongPasswordAndUnknownEmailLookIdentical() throws Exception {
        registerCustomer("real@example.com", "secret123");

        for (String email : new String[]{"real@example.com", "ghost@example.com"}) {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"email":"%s","password":"wrong-password"}
                                    """.formatted(email)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.detail").value("Invalid email or password"));
        }
    }

    @Test
    void disabledAccountCannotLogIn() throws Exception {
        registerCustomer("blocked@example.com", "secret123");
        jdbc.update("UPDATE users SET active = FALSE WHERE email = 'blocked@example.com'");

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"blocked@example.com","password":"secret123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    @Test
    void seededAdminCanLogIn() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(ADMIN_EMAIL, ADMIN_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(3600));
    }

    @Test
    void protectedEndpointWithoutTokenIs401ProblemDetail() throws Exception {
        mvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void garbageTokenIs401InvalidToken() throws Exception {
        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer("not.a.jwt")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void expiredTokenIs401TokenExpired() throws Exception {
        registerCustomer("late@example.com", "secret123");
        Long id = jdbc.queryForObject("SELECT id FROM users WHERE email = 'late@example.com'", Long.class);
        // Same secret as the app, but issued two hours ago.
        JwtService past = new JwtService(jwtProperties,
                Clock.fixed(Instant.now().minus(2, ChronoUnit.HOURS), ZoneOffset.UTC));
        String expired = past.issue(AuthUser.fromToken(id, Role.CUSTOMER));

        mvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
    }

    @Test
    void customerCannotReachAdminApi() throws Exception {
        registerCustomer("cust@example.com", "secret123");
        String token = login("cust@example.com", "secret123");

        mvc.perform(get("/api/admin/cities").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void publicBrowsingNeedsNoToken() throws Exception {
        // No controller yet, so 404 — the point is it's NOT 401.
        mvc.perform(get("/api/restaurants"))
                .andExpect(status().isNotFound());
    }
}
