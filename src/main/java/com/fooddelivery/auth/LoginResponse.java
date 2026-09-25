package com.fooddelivery.auth;

import com.fooddelivery.user.Role;

public record LoginResponse(String accessToken, String tokenType, long expiresIn, Long userId, Role role) {
}
