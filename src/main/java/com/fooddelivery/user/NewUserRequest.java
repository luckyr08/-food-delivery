package com.fooddelivery.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Account details for any new user: self-registered customers and admin-created owners. */
public record NewUserRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 150) String email,
        // BCrypt only uses the first 72 bytes of a password.
        @NotBlank @Size(min = 8, max = 72) String password,
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
        String phone) {

    /** Runs during JSON deserialization, i.e. before Bean Validation sees the values. */
    public NewUserRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim();
        phone = phone == null || phone.isBlank() ? null : phone.trim();
    }
}
