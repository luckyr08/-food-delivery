package com.fooddelivery.delivery;

import com.fooddelivery.user.NewUserRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Account + partner profile in one request: a partner profile can't exist without its user (1:1). */
public record CreateDeliveryPartnerRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Email @Size(max = 150) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @Pattern(regexp = "^\\+?[0-9]{10,15}$", message = "must be 10-15 digits, optionally starting with +")
        String phone,
        @NotNull Long cityId,
        @NotNull VehicleType vehicleType) {

    public CreateDeliveryPartnerRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim();
        phone = phone == null || phone.isBlank() ? null : phone.trim();
    }

    NewUserRequest toNewUser() {
        return new NewUserRequest(name, email, password, phone);
    }
}
