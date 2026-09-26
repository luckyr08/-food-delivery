package com.fooddelivery.stockgate;

/** The gate (Redis) can't be reached. Callers fail OPEN: MySQL alone is still correct. */
public class StockGateUnavailableException extends RuntimeException {

    public StockGateUnavailableException(String message) {
        super(message);
    }
}
