package com.fooddelivery.menu;

import com.fooddelivery.restaurant.RestaurantBrowseService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class MenuBrowseService {

    private final MenuItemRepository menuItemRepository;
    private final RestaurantBrowseService restaurantBrowseService;

    public MenuBrowseService(MenuItemRepository menuItemRepository, RestaurantBrowseService restaurantBrowseService) {
        this.menuItemRepository = menuItemRepository;
        this.restaurantBrowseService = restaurantBrowseService;
    }

    /** Active items of a publicly visible restaurant (404 otherwise), sold-out items included. */
    @Transactional(readOnly = true)
    public List<MenuItemResponse> menu(Long restaurantId, String category, boolean vegOnly) {
        restaurantBrowseService.requirePublic(restaurantId);
        String categoryFilter = category == null || category.isBlank() ? null : category.trim();
        return menuItemRepository.findPublicMenu(restaurantId, categoryFilter, vegOnly).stream()
                .map(MenuItemResponse::from).toList();
    }
}
