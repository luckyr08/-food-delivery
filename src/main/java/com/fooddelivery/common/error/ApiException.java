package com.fooddelivery.common.error;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base for all expected business errors. Unchecked on purpose: Spring's @Transactional
 * rolls back automatically only for RuntimeExceptions.
 */
@Getter
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    protected ApiException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
