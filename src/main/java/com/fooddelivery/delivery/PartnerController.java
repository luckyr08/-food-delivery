package com.fooddelivery.delivery;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.order.OrderLifecycleService;
import com.fooddelivery.order.OrderResponse;
import com.fooddelivery.order.OrderStatusUpdateRequest;
import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Everything a delivery partner does: availability, finding and claiming orders, pickup, delivery. */
@RestController
@RequestMapping("/api/partner")
@PreAuthorize("hasRole('DELIVERY_PARTNER')")
public class PartnerController {

    private final DeliveryAssignmentService assignmentService;
    private final OrderLifecycleService lifecycleService;

    public PartnerController(DeliveryAssignmentService assignmentService, OrderLifecycleService lifecycleService) {
        this.assignmentService = assignmentService;
        this.lifecycleService = lifecycleService;
    }

    @GetMapping("/me")
    public DeliveryPartnerResponse me(@AuthenticationPrincipal AuthUser me) {
        return assignmentService.me(me.id());
    }

    @PatchMapping("/me/status")
    public DeliveryPartnerResponse setStatus(@AuthenticationPrincipal AuthUser me,
                                             @Valid @RequestBody PartnerStatusRequest request) {
        return assignmentService.setAvailability(me.id(), request.status());
    }

    @GetMapping("/orders/available")
    public PageResponse<AvailableOrderResponse> available(@AuthenticationPrincipal AuthUser me,
                                                          @RequestParam(defaultValue = "0") @Min(0) int page,
                                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return assignmentService.availableOrders(me.id(), page, size);
    }

    /** First partner to claim wins; everyone else gets 409 ORDER_ALREADY_ASSIGNED. */
    @PostMapping("/orders/{orderId}/claim")
    public OrderResponse claim(@AuthenticationPrincipal AuthUser me, @PathVariable Long orderId) {
        return assignmentService.claim(orderId, me.id());
    }

    /** 204 when the partner has no active delivery. */
    @GetMapping("/orders/current")
    public ResponseEntity<OrderResponse> current(@AuthenticationPrincipal AuthUser me) {
        return assignmentService.currentOrder(me.id())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** OUT_FOR_DELIVERY (after READY_FOR_PICKUP) or DELIVERED; validated by the state machine. */
    @PatchMapping("/orders/{orderId}/status")
    public OrderResponse updateStatus(@AuthenticationPrincipal AuthUser me, @PathVariable Long orderId,
                                      @Valid @RequestBody OrderStatusUpdateRequest request) {
        return lifecycleService.partnerUpdate(orderId, me.id(), request.status());
    }
}
