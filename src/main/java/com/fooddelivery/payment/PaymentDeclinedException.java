package com.fooddelivery.payment;

import com.fooddelivery.common.error.ApiException;
import com.fooddelivery.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** 402 Payment Required. The order was recorded, then cancelled by the saga's compensation step. */
public class PaymentDeclinedException extends ApiException {

    public PaymentDeclinedException(String reason, long orderId) {
        super(HttpStatus.PAYMENT_REQUIRED, ErrorCode.PAYMENT_DECLINED,
                "Payment declined: " + reason + " (order " + orderId + " was cancelled and its stock released)");
    }
}
