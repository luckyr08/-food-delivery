package com.fooddelivery.stockgate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Redis mode only: returns units of abandoned reservations, and periodically re-syncs hot items from MySQL. */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.stock-gate.mode", havingValue = "redis")
class StockGateScheduler {

    private final StockGate gate;
    private final JdbcTemplate jdbc;

    StockGateScheduler(StockGate gate, JdbcTemplate jdbc) {
        this.gate = gate;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelay = 5_000)
    void sweep() {
        try {
            int swept = gate.sweepExpired();
            if (swept > 0) {
                log.warn("Returned units of {} abandoned gate reservations", swept);
            }
        } catch (RuntimeException e) {
            log.warn("Stock gate sweep failed: {}", e.getMessage());
        }
    }

    /** Drift repair: MySQL is the source of truth. */
    @Scheduled(fixedDelay = 60_000)
    void resyncHotItems() {
        try {
            jdbc.query("SELECT id, stock FROM menu_items WHERE flash_sale = TRUE AND active = TRUE AND stock IS NOT NULL",
                    rs -> {
                        gate.resync(rs.getLong("id"), rs.getInt("stock"));
                    });
        } catch (RuntimeException e) {
            log.warn("Stock gate resync failed: {}", e.getMessage());
        }
    }
}
