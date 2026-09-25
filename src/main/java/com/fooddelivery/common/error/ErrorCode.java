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
    INVALID_CREDENTIALS,
    ACCOUNT_DISABLED,
    TOKEN_EXPIRED,
    INVALID_TOKEN,
    ACCESS_DENIED,
    CONFLICT,
    DATA_INTEGRITY_VIOLATION,
    CONCURRENT_MODIFICATION,
    INTERNAL_ERROR,

    // auth / users
    EMAIL_ALREADY_REGISTERED,
    CANNOT_DEACTIVATE_SELF,

    // cities / restaurants
    CITY_ALREADY_EXISTS,
    CITY_INACTIVE,
    INVALID_OWNER
}
