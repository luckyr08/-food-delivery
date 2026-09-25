package com.fooddelivery.common.error;

import org.springframework.http.HttpStatus;

/** 409. The request is valid but the current state of the data doesn't allow it. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
