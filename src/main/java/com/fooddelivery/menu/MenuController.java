package com.fooddelivery.menu;

import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Public menu. Not paginated: a menu is small and shown in full. */
@RestController
@RequestMapping("/api/restaurants/{restaurantId}/menu")
public class MenuController {

    private final MenuBrowseService service;

    public MenuController(MenuBrowseService service) {
        this.service = service;
    }

    @GetMapping
    public List<MenuItemResponse> menu(@PathVariable Long restaurantId,
                                       @RequestParam(required = false) @Size(max = 100) String category,
                                       @RequestParam(defaultValue = "false") boolean vegOnly) {
        return service.menu(restaurantId, category, vegOnly);
    }
}
