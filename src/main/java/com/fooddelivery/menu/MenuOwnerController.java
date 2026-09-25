package com.fooddelivery.menu;

import com.fooddelivery.security.AuthUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/owner/restaurants/{restaurantId}/menu-items")
@PreAuthorize("hasRole('RESTAURANT_OWNER')")
public class MenuOwnerController {

    private final MenuOwnerService service;

    public MenuOwnerController(MenuOwnerService service) {
        this.service = service;
    }

    @GetMapping
    public List<MenuItemOwnerResponse> list(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId) {
        return service.list(restaurantId, me.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MenuItemOwnerResponse create(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                                        @Valid @RequestBody MenuItemRequest request) {
        return service.create(restaurantId, me.id(), request);
    }

    @PatchMapping("/{itemId}")
    public MenuItemOwnerResponse update(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                                        @PathVariable Long itemId, @Valid @RequestBody MenuItemUpdateRequest request) {
        return service.update(restaurantId, itemId, me.id(), request);
    }

    @PutMapping("/{itemId}/stock")
    public MenuItemOwnerResponse setStock(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                                          @PathVariable Long itemId, @Valid @RequestBody StockRequest request) {
        return service.setStock(restaurantId, itemId, me.id(), request.stock());
    }

    @DeleteMapping("/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser me, @PathVariable Long restaurantId,
                       @PathVariable Long itemId) {
        service.delete(restaurantId, itemId, me.id());
    }
}
