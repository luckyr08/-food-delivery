package com.fooddelivery.order;

import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
@PreAuthorize("hasRole('CUSTOMER')")
public class OrderController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotent-Replayed";

    private final OrderService orderService;
    private final OrderLifecycleService lifecycleService;

    public OrderController(OrderService orderService, OrderLifecycleService lifecycleService) {
        this.orderService = orderService;
        this.lifecycleService = lifecycleService;
    }

    /** 201 for a new order; 200 + Idempotent-Replayed: true when the key matched an earlier one. */
    @PostMapping
    public ResponseEntity<OrderResponse> place(
            @AuthenticationPrincipal AuthUser me,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) @Size(min = 8, max = 64) String idempotencyKey,
            @Valid @RequestBody PlaceOrderRequest request) {
        PlacementResult result = orderService.placeOrder(me.id(), request, idempotencyKey);
        if (result.replayed()) {
            return ResponseEntity.ok().header(REPLAYED, "true").body(result.order());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.order());
    }

    @GetMapping
    public PageResponse<OrderSummaryResponse> myOrders(@AuthenticationPrincipal AuthUser me,
                                                       @RequestParam(defaultValue = "0") @Min(0) int page,
                                                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return orderService.listForCustomer(me.id(), page, size);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return orderService.getForCustomer(id, me.id());
    }

    /** Allowed while PLACED or ACCEPTED; refunds and restocks. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthUser me, @PathVariable Long id,
                                @Valid @RequestBody(required = false) CancelOrderRequest request) {
        return lifecycleService.customerCancel(id, me.id(), request == null ? null : request.reason());
    }

    @GetMapping("/{id}/timeline")
    public List<TimelineEntry> timeline(@AuthenticationPrincipal AuthUser me, @PathVariable Long id) {
        return orderService.timelineForCustomer(id, me.id());
    }
}
