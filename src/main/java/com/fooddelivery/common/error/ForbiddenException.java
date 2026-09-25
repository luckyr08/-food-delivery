package com.fooddelivery.common.error;

import org.springframework.http.HttpStatus;

/** 403. Caller is authenticated but may not perform this action. */
public class ForbiddenException extends ApiException {

    public ForbiddenException(ErrorCode code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
