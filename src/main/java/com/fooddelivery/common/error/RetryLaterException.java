package com.fooddelivery.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 503 (overloaded) or 429 (rate limited): the request was fine, try again after Retry-After seconds. */
@Getter
public class RetryLaterException extends ApiException {

    private final long retryAfterSeconds;

    public RetryLaterException(HttpStatus status, ErrorCode code, String message, long retryAfterSeconds) {
        super(status, code, message);
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }
}
