package com.fooddelivery.common.error;

import org.springframework.http.HttpStatus;

/** 400. Business-rule violation in the request itself; retrying unchanged will always fail. */
public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
