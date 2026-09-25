package com.fooddelivery.city;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/cities")
@PreAuthorize("hasRole('ADMIN')")
public class CityAdminController {

    private final CityService cityService;

    public CityAdminController(CityService cityService) {
        this.cityService = cityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CityResponse create(@Valid @RequestBody CityRequest request) {
        return cityService.create(request);
    }

    @PatchMapping("/{id}")
    public CityResponse update(@PathVariable Long id, @Valid @RequestBody CityUpdateRequest request) {
        return cityService.update(id, request);
    }

    /** Includes inactive cities. The set of cities is small, so no pagination. */
    @GetMapping
    public List<CityResponse> list() {
        return cityService.listAll();
    }
}
