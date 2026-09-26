package com.fooddelivery.stockgate;

import com.fooddelivery.menu.MenuItem;
import com.fooddelivery.menu.MenuItemRepository;
import com.fooddelivery.outbox.OutboxWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Wraps the gate for checkout. Only limited-stock flash-sale items are gated. If the gate is down we fail OPEN:
 * the order proceeds on MySQL alone, which is still correct (just slower under a spike).
 */
@Slf4j
@Service
public class StockGateService {

    private final StockGate gate;
    private final MenuItemRepository menuItemRepository;
    private final OutboxWriter outboxWriter;

    public StockGateService(StockGate gate, MenuItemRepository menuItemRepository, OutboxWriter outboxWriter) {
        this.gate = gate;
        this.menuItemRepository = menuItemRepository;
        this.outboxWriter = outboxWriter;
    }

    /** Before the MySQL transaction. Throws StockGateRejectedException (409) for losers. */
    public GateReservation admit(long customerId, Map<Long, Integer> quantityByItem) {
        if (!gate.enabled()) {
            return GateReservation.NONE;
        }
        List<MenuItem> hot = menuItemRepository.findAllById(quantityByItem.keySet()).stream()
                .filter(m -> m.isFlashSale() && m.getStock() != null).toList();
        if (hot.isEmpty()) {
            return GateReservation.NONE;
        }
        try {
            hot.forEach(m -> gate.initIfAbsent(m.getId(), m.getStock())); // cache miss -> load from MySQL
            return gate.reserve(customerId, hot.stream()
                    .map(m -> new GateLine(m.getId(), quantityByItem.get(m.getId()))).toList());
        } catch (StockGateUnavailableException e) {
            log.warn("Stock gate unavailable, falling back to MySQL only: {}", e.getMessage());
            return GateReservation.NONE;
        }
    }

    public void confirm(GateReservation reservation) {
        quietly(() -> gate.confirm(reservation));
    }

    public void release(GateReservation reservation) {
        quietly(() -> gate.release(reservation)); // if this fails, the sweeper returns the units after the TTL
    }

    /** Inside a transaction that restocked a hot item in MySQL: re-sync the counter after commit (outbox). */
    public void afterMysqlRestock(MenuItem item) {
        if (gate.enabled() && item.isFlashSale() && item.getStock() != null) {
            outboxWriter.stockGateResync(item.getId());
        }
    }

    private static void quietly(Runnable action) {
        try {
            action.run();
        } catch (StockGateUnavailableException e) {
            log.warn("Stock gate unavailable: {}", e.getMessage());
        }
    }
}
