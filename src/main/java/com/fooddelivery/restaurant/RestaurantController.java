package com.fooddelivery.restaurant;

import com.fooddelivery.common.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public browsing (no login). */
@RestController
@RequestMapping("/api/restaurants")
public class RestaurantController {

    private final RestaurantBrowseService service;

    public RestaurantController(RestaurantBrowseService service) {
        this.service = service;
    }

    /** City-scoped like real food apps; open restaurants first, then by name. */
    @GetMapping
    public PageResponse<RestaurantSummaryResponse> browse(
            @RequestParam Long cityId,
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Size(max = 100) String cuisine,
            @RequestParam(defaultValue = "false") boolean openOnly,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.browse(cityId, q, cuisine, openOnly, page, size);
    }

    @GetMapping("/{id}")
    public RestaurantSummaryResponse get(@PathVariable Long id) {
        return service.get(id);
    }
}
