package com.fooddelivery.city;

public record CityResponse(Long id, String name, String state, boolean active) {

    public static CityResponse from(City city) {
        return new CityResponse(city.getId(), city.getName(), city.getState(), city.isActive());
    }
}
