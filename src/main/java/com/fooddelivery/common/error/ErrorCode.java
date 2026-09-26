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
    SERVICE_BUSY,
    RATE_LIMITED,
    INTERNAL_ERROR,

    // auth / users
    EMAIL_ALREADY_REGISTERED,
    CANNOT_DEACTIVATE_SELF,

    // cities / restaurants
    CITY_ALREADY_EXISTS,
    CITY_INACTIVE,
    INVALID_OWNER,
    RESTAURANT_INACTIVE,

    // orders / payments
    RESTAURANT_CLOSED,
    ITEM_NOT_IN_RESTAURANT,
    ITEM_UNAVAILABLE,
    INSUFFICIENT_STOCK,
    DUPLICATE_ORDER_ITEM,
    IDEMPOTENCY_KEY_REUSED,
    PAYMENT_DECLINED,
    INVALID_WEBHOOK_SIGNATURE,
    PAYMENT_AMOUNT_MISMATCH,
    INVALID_STATUS_TRANSITION,
    TRANSITION_NOT_ALLOWED_FOR_ROLE,
    REASON_REQUIRED,

    // delivery
    ORDER_NOT_CLAIMABLE,
    ORDER_ALREADY_ASSIGNED,
    PARTNER_NOT_AVAILABLE,
    PARTNER_BUSY,
    INVALID_PARTNER_STATUS,

    // reviews
    ORDER_NOT_DELIVERED,
    ALREADY_REVIEWED,
    REVIEW_WINDOW_CLOSED,
    NO_PARTNER_TO_RATE
}
