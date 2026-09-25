package com.fooddelivery.rating;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping("/api/orders/{orderId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('CUSTOMER')")
    public ReviewResponse submit(@AuthenticationPrincipal AuthUser me, @PathVariable Long orderId,
                                 @Valid @RequestBody ReviewRequest request) {
        return reviewService.submit(orderId, me.id(), request);
    }

    @GetMapping("/api/orders/{orderId}/review")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ReviewResponse mine(@AuthenticationPrincipal AuthUser me, @PathVariable Long orderId) {
        return reviewService.getForCustomer(orderId, me.id());
    }

    /** Public (GET /api/restaurants/** is open): newest first. */
    @GetMapping("/api/restaurants/{restaurantId}/reviews")
    public PageResponse<PublicReviewResponse> forRestaurant(@PathVariable Long restaurantId,
                                                            @RequestParam(defaultValue = "0") @Min(0) int page,
                                                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return reviewService.listForRestaurant(restaurantId, page, size);
    }
}
