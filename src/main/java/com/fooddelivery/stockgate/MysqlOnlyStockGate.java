package com.fooddelivery.stockgate;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/** app.stock-gate.mode=mysql-only (default): no gate; MySQL's conditional UPDATE does everything. */
@Component
@ConditionalOnProperty(name = "app.stock-gate.mode", havingValue = "mysql-only", matchIfMissing = true)
public class MysqlOnlyStockGate implements StockGate {

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public void initIfAbsent(long menuItemId, int mysqlStock) {
    }

    @Override
    public GateReservation reserve(long customerId, List<GateLine> lines) {
        return GateReservation.NONE;
    }

    @Override
    public void confirm(GateReservation reservation) {
    }

    @Override
    public void release(GateReservation reservation) {
    }

    @Override
    public void resync(long menuItemId, int mysqlStock) {
    }

    @Override
    public int sweepExpired() {
        return 0;
    }
}
