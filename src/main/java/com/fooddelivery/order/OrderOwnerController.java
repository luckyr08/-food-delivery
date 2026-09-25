package com.fooddelivery.order;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Restaurant side of the lifecycle: the order queue and accept/reject/preparing/ready. */
@RestController
@RequestMapping("/api/owner/restaurants/{restaurantId}/orders")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class OrderOwnerController {

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;

    public OrderOwnerController(OrderService orderService, OrderLifecycleService lifecycleService) {
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> queue(@AuthenticationPrincipal AuthUser me,
                                                    @PathVariable Long restaurantId,
                                                    @RequestParam(required = false) OrderStatus status,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.listForRestaurant(restaurantId, me.id(), status, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderResponse get(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                             @PathVariable Long orderId) {
        return orderService.getForRestaurant(restaurantId, orderId, me.id());
    }

    /** One endpoint for all owner transitions; the state machine decides what's allowed. */
    @PatchMapping("/{orderId}/status")
    public OrderResponse updateStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                                      @PathVariable Long orderId, @Valid @RequestBody OrderStatusUpdateRequest request) {
        return lifecycleService.ownerUpdate(restaurantId, orderId, me.id(), request.status(), request.reason());
    }
}
