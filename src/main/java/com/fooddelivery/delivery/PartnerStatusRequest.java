package com.fooddelivery.delivery;

import jakarta.validation.constraints.NotNull;

/** Partners toggle AVAILABLE / OFFLINE themselves; BUSY is set only by the system. */
public record PartnerStatusRequest(@NotNull PartnerStatus status) {
}
