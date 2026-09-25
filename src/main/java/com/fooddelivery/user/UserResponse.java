package com.fooddelivery.user;

import java.time.Instant;

/** Public view of a user. Never exposes the password hash. */
public record UserResponse(Long id, String name, String email, String phone, Role role, boolean active,
                           Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.getRole(), user.isActive(), user.getCreatedAt());
    }
}
