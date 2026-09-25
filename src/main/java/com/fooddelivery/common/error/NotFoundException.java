package com.fooddelivery.common.error;

import org.springframework.http.HttpStatus;

/** 404. Also used when a resource exists but belongs to someone else, to avoid leaking its existence. */
public class NotFoundException extends ApiException {

    public NotFoundException(ErrorCode code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }

    public NotFoundException(String resource, Object id) {
        this(ErrorCode.RESOURCE_NOT_FOUND, resource + " " + id + " not found");
    }
}
