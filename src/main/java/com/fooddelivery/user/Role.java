package com.fooddelivery.user;

public enum Role {
    ADMIN,
    RESTAURANT_OWNER,
    CUSTOMER,
    DELIVERY_PARTNER,
    /**
     * Automated transitions (payment confirmation, reconciler). Never assigned to a user account:
     * registration and admin onboarding only create the roles above.
     */
    SYSTEM
}
