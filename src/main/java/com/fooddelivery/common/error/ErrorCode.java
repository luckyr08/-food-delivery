package com.fooddelivery.common.error;

/**
 * Stable, machine-readable error codes returned in the "code" field.
 * Clients branch on these; the human-readable "detail" may change wording.
 * Feature-specific codes are added alongside their features.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    INVALID_REQUEST,
    RESOURCE_NOT_FOUND,
    UNAUTHORIZED,
    ACCESS_DENIED,
    CONFLICT,
    DATA_INTEGRITY_VIOLATION,
    CONCURRENT_MODIFICATION,
    INTERNAL_ERROR
}
