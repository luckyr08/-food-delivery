package com.fooddelivery.delivery;

import com.fooddelivery.common.Ratings;

import java.math.BigDecimal;

public record DeliveryPartnerResponse(Long id, Long userId, String name, String email, String phone, boolean active,
                                      Long cityId, String cityName, VehicleType vehicleType, PartnerStatus status,
                                      BigDecimal averageRating, int ratingCount) {

    /** Expects user and city to be loaded (entity graph). */
    public static DeliveryPartnerResponse from(DeliveryPartner p) {
        var user = p.getUser();
        return new DeliveryPartnerResponse(p.getId(), user.getId(), user.getName(), user.getEmail(), user.getPhone(),
                user.isActive(), p.getCity().getId(), p.getCity().getName(), p.getVehicleType(), p.getStatus(),
                Ratings.average(p.getRatingSum(), p.getRatingCount()), p.getRatingCount());
    }
}
