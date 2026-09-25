package com.fooddelivery.auth;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(@NotBlank String email, @NotBlank String password) {

    public LoginRequest {
        email = email == null ? null : email.trim();
    }
}
