package com.fooddelivery.restaurant;

import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/owner/restaurants")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class RestaurantOwnerController {

    private final RestaurantOwnerService service;

    public RestaurantOwnerController(RestaurantOwnerService service) {
        this.service = service;
    }

    @GetMapping
    public List<RestaurantResponse> myRestaurants(@AuthenticationPrincipal AuthUser me) {
        return service.myRestaurants(me.id());
    }

    @PatchMapping("/{id}/status")
    public RestaurantResponse setOpen(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                      @Valid @RequestBody OpenStatusRequest request) {
        return service.setOpen(id, me.id(), request.open());
    }
}
