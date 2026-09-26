package com.fooddelivery.stockgate;

import com.fooddelivery.outbox.OutboxHandler;
import com.fooddelivery.outbox.OutboxWriter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * After a hot item's MySQL stock went back up (cancel, decline, expiry), re-sync its Redis counter from MySQL.
 * Idempotent and coalescing-friendly: it always recomputes from the source of truth.
 */
@Component
@ConditionalOnProperty(name = "app.stock-gate.mode", havingValue = "redis")
class StockGateResyncHandler implements OutboxHandler {

    private final StockGate gate;
    private final JdbcTemplate jdbc;

    StockGateResyncHandler(StockGate gate, JdbcTemplate jdbc) {
        this.gate = gate;
        this.jdbc = jdbc;
    }

    @Override
    public String aggregateType() {
        return OutboxWriter.STOCK_GATE;
    }

    @Override
    public void handle(Set<Long> menuItemIds) {
        for (Long id : menuItemIds) {
            Integer stock = jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, id);
            if (stock != null) {
                gate.resync(id, stock);
            }
        }
    }
}
