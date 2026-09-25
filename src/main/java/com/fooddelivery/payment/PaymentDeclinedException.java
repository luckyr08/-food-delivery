package com.fooddelivery.payment;

import com.fooddelivery.common.error.ApiException;
import com.fooddelivery.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 402 Payment Required. Thrown inside the placement transaction, so everything rolls back. */
public class PaymentDeclinedException extends ApiException {

    public PaymentDeclinedException(String reason) {
        super(HttpStatus.PAYMENT_REQUIRED, ErrorCode.PAYMENT_DECLINED, "Payment declined: " + reason);
    }
}
