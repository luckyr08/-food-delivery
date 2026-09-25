package com.fooddelivery.order;

import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
@PreAuthorize("hasRole('ADMIN')")
public class OrderAdminController {

    private final OrderLifecycleService lifecycleService;

    public OrderAdminController(OrderLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    /** Support override: any non-final order, reason required. */
    @PostMapping("/{id}/cancel")
    public OrderResponse cancel(@AuthenticationPrincipal AuthUser admin, @PathVariable Long id,
                                @Valid @RequestBody CancelOrderRequest request) {
        return lifecycleService.adminCancel(id, admin.id(), request.reason());
    }
}
