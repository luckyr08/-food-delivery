package com.fooddelivery.search;

import com.fooddelivery.common.Ratings;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the CURRENT state of restaurants from MySQL into search documents. Must run inside a transaction:
 * both queries then read one consistent snapshot (REPEATABLE READ), so a document and its version match.
 */
@Component
class RestaurantDocumentLoader {

    /** visible = documents to upsert; versions = search_version of every requested restaurant (for deletes). */
    record Loaded(Map<Long, RestaurantDocument> visible, Map<Long, Long> versions) {
    }

    private static final String RESTAURANTS = """
            SELECT r.id, r.name, r.address, r.cuisine, r.is_open, r.active, r.rating_sum, r.rating_count,
                   r.search_version, c.id AS city_id, c.name AS city_name, c.active AS city_active
            FROM restaurants r JOIN cities c ON c.id = r.city_id
            """;
    private static final String MENU = """
            SELECT id, restaurant_id, name, category, price, is_veg, available
            FROM menu_items WHERE active = TRUE AND restaurant_id IN (:ids)
            ORDER BY category, name
            """;

    private final NamedParameterJdbcTemplate jdbc;

    RestaurantDocumentLoader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Loaded load(Collection<Long> ids) {
        return loadWhere(" WHERE r.id IN (:ids)", new MapSqlParameterSource("ids", ids));
    }

    Loaded loadAll() {
        return loadWhere("", new MapSqlParameterSource());
    }

    private Loaded loadWhere(String where, MapSqlParameterSource params) {
        Map<Long, Long> versions = new HashMap<>();
        Map<Long, Row> visibleRows = new HashMap<>();
        jdbc.query(RESTAURANTS + where, params, rs -> {
            long id = rs.getLong("id");
            versions.put(id, rs.getLong("search_version"));
            // Only publicly visible restaurants are in the index; others become versioned deletes.
            if (rs.getBoolean("active") && rs.getBoolean("city_active")) {
                visibleRows.put(id, new Row(id, rs.getString("name"), rs.getString("address"), rs.getString("cuisine"),
                        rs.getLong("city_id"), rs.getString("city_name"), rs.getBoolean("is_open"),
                        rs.getInt("rating_sum"), rs.getInt("rating_count"), rs.getLong("search_version")));
            }
        });
        Map<Long, List<MenuDocument>> menus = new HashMap<>();
        if (!visibleRows.isEmpty()) {
            jdbc.query(MENU, new MapSqlParameterSource("ids", visibleRows.keySet()), rs -> {
                menus.computeIfAbsent(rs.getLong("restaurant_id"), k -> new ArrayList<>())
                        .add(new MenuDocument(rs.getLong("id"), rs.getString("name"), rs.getString("category"),
                                rs.getBigDecimal("price"), rs.getBoolean("is_veg"), rs.getBoolean("available")));
            });
        }
        Map<Long, RestaurantDocument> visible = new HashMap<>();
        visibleRows.forEach((id, r) -> visible.put(id, new RestaurantDocument(r.id, r.name, r.address, r.cuisine,
                r.cityId, r.cityName, r.open, Ratings.average(r.ratingSum, r.ratingCount), r.ratingCount,
                List.copyOf(menus.getOrDefault(id, List.of())), r.version)));
        return new Loaded(visible, versions);
    }

    private record Row(long id, String name, String address, String cuisine, long cityId, String cityName,
                       boolean open, int ratingSum, int ratingCount, long version) {
    }
}
