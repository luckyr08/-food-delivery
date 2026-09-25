package com.fooddelivery.restaurant;

import jakarta.validation.constraints.NotNull;

public record OpenStatusRequest(@NotNull Boolean open) {
}
