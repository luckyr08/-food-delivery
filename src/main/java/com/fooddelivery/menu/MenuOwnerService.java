package com.fooddelivery.menu;

import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.restaurant.Restaurant;
import com.fooddelivery.restaurant.RestaurantOwnerService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Every method first proves ownership of the restaurant, then of the item within it. */
@Service
public class MenuOwnerService {

    private final MenuItemRepository menuItemRepository;
    private final RestaurantOwnerService restaurantOwnerService;

    public MenuOwnerService(MenuItemRepository menuItemRepository, RestaurantOwnerService restaurantOwnerService) {
        this.menuItemRepository = menuItemRepository;
        this.restaurantOwnerService = restaurantOwnerService;
    }

    @Transactional(readOnly = true)
    public List<MenuItemOwnerResponse> list(Long restaurantId, Long ownerId) {
        restaurantOwnerService.requireOwned(restaurantId, ownerId);
        return menuItemRepository.findByRestaurantIdAndActiveTrueOrderByCategoryAscNameAsc(restaurantId).stream()
                .map(MenuItemOwnerResponse::from).toList();
    }

    @Transactional
    public MenuItemOwnerResponse create(Long restaurantId, Long ownerId, MenuItemRequest request) {
        Restaurant restaurant = restaurantOwnerService.requireOwned(restaurantId, ownerId);
        MenuItem item = new MenuItem();
        item.setRestaurant(restaurant);
        item.setName(request.name());
        item.setDescription(request.description());
        item.setCategory(request.category());
        item.setPrice(request.price());
        item.setVeg(request.veg() == null || request.veg());
        item.setAvailable(request.available() == null || request.available());
        item.setStock(request.stock());
        return MenuItemOwnerResponse.from(menuItemRepository.save(item));
    }

    /** Price changes only affect new orders: order_items keep a price snapshot. */
    @Transactional
    public MenuItemOwnerResponse update(Long restaurantId, Long itemId, Long ownerId, MenuItemUpdateRequest request) {
        MenuItem item = requireOwnedItem(restaurantId, itemId, ownerId);
        if (request.name() != null) {
            item.setName(request.name());
        }
        if (request.description() != null) {
            item.setDescription(request.description().isEmpty() ? null : request.description());
        }
        if (request.category() != null) {
            item.setCategory(request.category().isEmpty() ? null : request.category());
        }
        if (request.price() != null) {
            item.setPrice(request.price());
        }
        if (request.veg() != null) {
            item.setVeg(request.veg());
        }
        if (request.available() != null) {
            item.setAvailable(request.available());
        }
        return MenuItemOwnerResponse.from(item);
    }

    /**
     * Absolute set ("I have 25 left"). Saved through the entity, so @Version is checked on commit:
     * if an order decremented stock (which bumps version) in between, this fails with 409 instead of
     * silently overwriting the sale.
     */
    @Transactional
    public MenuItemOwnerResponse setStock(Long restaurantId, Long itemId, Long ownerId, Integer stock) {
        MenuItem item = requireOwnedItem(restaurantId, itemId, ownerId);
        item.setStock(stock);
        return MenuItemOwnerResponse.from(item);
    }

    /** Soft delete: past order_items still reference the row. */
    @Transactional
    public void delete(Long restaurantId, Long itemId, Long ownerId) {
        requireOwnedItem(restaurantId, itemId, ownerId).setActive(false);
    }

    private MenuItem requireOwnedItem(Long restaurantId, Long itemId, Long ownerId) {
        restaurantOwnerService.requireOwned(restaurantId, ownerId);
        return menuItemRepository.findByIdAndRestaurantIdAndActiveTrue(itemId, restaurantId)
                .orElseThrow(() -> new NotFoundException("Menu item", itemId));
    }
}
