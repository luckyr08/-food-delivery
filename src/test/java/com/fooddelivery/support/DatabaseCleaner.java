package com.fooddelivery.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Statement;
import java.util.List;

/** Empties every table except Flyway's history; users keep only the seeded admin. */
public final class DatabaseCleaner {

    public static final String ADMIN_EMAIL = "admin@fooddelivery.com";

    private DatabaseCleaner() {
    }

    public static void clean(JdbcTemplate jdbc) {
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
}
