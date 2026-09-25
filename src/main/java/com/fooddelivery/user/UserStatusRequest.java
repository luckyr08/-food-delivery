package com.fooddelivery.user;

import jakarta.validation.constraints.NotNull;

public record UserStatusRequest(@NotNull Boolean active) {
}
