package com.fooddelivery.delivery;

/** Admin PATCH: null = leave unchanged. Status is controlled by the partner, not the admin. */
public record UpdateDeliveryPartnerRequest(Long cityId, VehicleType vehicleType) {
}
