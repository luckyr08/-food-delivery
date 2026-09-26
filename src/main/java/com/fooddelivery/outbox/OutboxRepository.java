package com.fooddelivery.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Plain SQL on purpose: the claim query needs FOR UPDATE SKIP LOCKED, and every statement here should be
 * readable as-is. JdbcTemplate joins the surrounding JPA transaction (same connection).
 */
@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public OutboxRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    public void append(String aggregateType, long aggregateId, String eventType) {
        jdbc.update("INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, created_at, next_attempt_at) "
                + "VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))", aggregateType, aggregateId, eventType);
    }

    /** One event per restaurant in a city, in a single statement. */
    public int appendForCityRestaurants(long cityId, String eventType) {
        return jdbc.update("INSERT INTO outbox_event (aggregate_type, aggregate_id, event_type, created_at, next_attempt_at) "
                + "SELECT 'RESTAURANT', id, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) FROM restaurants WHERE city_id = ?",
                eventType, cityId);
    }

    /**
     * Claims due rows and keeps them locked until the relay's transaction ends. SKIP LOCKED makes
     * concurrent relays (several app instances) take disjoint batches instead of waiting on each other.
     */
    public List<OutboxEvent> claimBatch(int limit) {
        return jdbc.query("""
                        SELECT id, aggregate_type, aggregate_id, event_type, attempts
                        FROM outbox_event
                        WHERE processed_at IS NULL AND next_attempt_at <= UTC_TIMESTAMP(6)
                        ORDER BY id
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, i) -> new OutboxEvent(rs.getLong("id"), rs.getString("aggregate_type"),
                        rs.getLong("aggregate_id"), rs.getString("event_type"), rs.getInt("attempts")),
                limit);
    }

    public void markProcessed(Collection<Long> ids) {
        named.update("UPDATE outbox_event SET processed_at = UTC_TIMESTAMP(6), last_error = NULL WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", ids));
    }

    /** Exponential backoff: 2, 4, 8 ... seconds, capped at 5 minutes. Rows are never dropped. */
    public void markFailed(Collection<Long> ids, String error) {
        named.update("""
                        UPDATE outbox_event
                        SET attempts = attempts + 1,
                            next_attempt_at = UTC_TIMESTAMP(6) + INTERVAL LEAST(POW(2, attempts + 1), 300) SECOND,
                            last_error = :error
                        WHERE id IN (:ids)
                        """,
                new MapSqlParameterSource(Map.of("ids", ids, "error", truncate(error))));
    }

    public int purgeProcessedOlderThanDays(int days) {
        return jdbc.update("DELETE FROM outbox_event WHERE processed_at < UTC_TIMESTAMP(6) - INTERVAL ? DAY LIMIT 10000",
                days);
    }

    public Map<String, Object> stats() {
        return jdbc.queryForMap("""
                SELECT COALESCE(SUM(processed_at IS NULL), 0)                  AS pending,
                       COALESCE(SUM(processed_at IS NULL AND attempts > 0), 0) AS retrying
                FROM outbox_event
                """);
    }

    private static String truncate(String s) {
        String value = s == null ? "unknown error" : s;
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
