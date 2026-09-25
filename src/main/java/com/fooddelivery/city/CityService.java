package com.fooddelivery.city;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CityService {

    private final CityRepository cityRepository;

    public CityService(CityRepository cityRepository) {
        this.cityRepository = cityRepository;
    }

    @Transactional
    public CityResponse create(CityRequest request) {
        if (cityRepository.existsByName(request.name())) {
            throw duplicate(request.name());
        }
        City city = new City();
        city.setName(request.name());
        city.setState(request.state());
        return CityResponse.from(cityRepository.save(city));
    }

    @Transactional
    public CityResponse update(Long id, CityUpdateRequest request) {
        City city = require(id);
        if (request.name() != null && !request.name().equals(city.getName())) {
            if (cityRepository.existsByNameAndIdNot(request.name(), id)) {
                throw duplicate(request.name());
            }
            city.setName(request.name());
        }
        if (request.state() != null) {
            city.setState(request.state().isEmpty() ? null : request.state());
        }
        if (request.active() != null) {
            // Soft delete: restaurants/partners in the city are hidden by browse queries, not modified.
            city.setActive(request.active());
        }
        return CityResponse.from(city); // dirty checking flushes the changes on commit
    }

    @Transactional(readOnly = true)
    public List<CityResponse> listAll() {
        return cityRepository.findAllByOrderByNameAsc().stream().map(CityResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public List<CityResponse> listActive() {
        return cityRepository.findByActiveTrueOrderByNameAsc().stream().map(CityResponse::from).toList();
    }

    /** For other features: the city must exist (404) and be active (400) to attach new things to it. */
    @Transactional(readOnly = true)
    public City requireActive(Long id) {
        City city = require(id);
        if (!city.isActive()) {
            throw new BadRequestException(ErrorCode.CITY_INACTIVE, "City " + id + " is not active");
        }
        return city;
    }

    private City require(Long id) {
        return cityRepository.findById(id).orElseThrow(() -> new NotFoundException("City", id));
    }

    private static ConflictException duplicate(String name) {
        return new ConflictException(ErrorCode.CITY_ALREADY_EXISTS, "City '" + name + "' already exists");
    }
}
