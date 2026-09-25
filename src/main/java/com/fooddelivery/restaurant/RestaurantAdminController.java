package com.fooddelivery.restaurant;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.user.NewUserRequest;
import com.fooddelivery.user.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class RestaurantAdminController {

    private final RestaurantAdminService service;

    public RestaurantAdminController(RestaurantAdminService service) {
        this.service = service;
    }

    @PostMapping("/restaurant-owners")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createOwner(@Valid @RequestBody NewUserRequest request) {
        return service.createOwner(request);
    }

    @PostMapping("/restaurants")
    @ResponseStatus(HttpStatus.CREATED)
    public RestaurantResponse create(@Valid @RequestBody CreateRestaurantRequest request) {
        return service.create(request);
    }

    @PatchMapping("/restaurants/{id}")
    public RestaurantResponse update(@PathVariable Long id, @Valid @RequestBody UpdateRestaurantRequest request) {
        return service.update(id, request);
    }

    /** Sorting is fixed server-side: client-chosen sort fields could order by sensitive columns. */
    @GetMapping("/restaurants")
    public PageResponse<RestaurantResponse> search(@RequestParam(required = false) Long cityId,
                                                   @RequestParam(required = false) Boolean active,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.search(cityId, active, page, size);
    }
}
