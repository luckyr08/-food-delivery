package com.fooddelivery.stockgate;

import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;

/** 409 decided by the gate in ~1 ms, without touching MySQL. */
public class StockGateRejectedException extends ConflictException {

    public StockGateRejectedException(ErrorCode code, String message) {
        super(code, message);
    }
}
