package com.fooddelivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Context startup already proves every entity matches the Flyway schema (ddl-auto=validate).
 * These checks cover the seed data and a DB-level guard.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seededAdminExistsWithWorkingPassword() {
        var admin = jdbc.queryForMap(
                "SELECT role, password_hash FROM users WHERE email = 'admin@fooddelivery.com'");

        assertThat(admin.get("role")).isEqualTo("ADMIN");
        assertThat(new BCryptPasswordEncoder().matches("Admin@123", (String) admin.get("password_hash")))
                .isTrue();
    }

    @Test
    void stockCheckConstraintIsInstalled() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = DATABASE() AND CONSTRAINT_NAME = 'chk_menu_items_stock'",
                Integer.class)).isEqualTo(1);
    }
}
