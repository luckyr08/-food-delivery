package com.fooddelivery.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {

    /**
     * The item must belong to the given (already ownership-checked) restaurant. Checking only the
     * restaurant would let an owner pass their own restaurant id with a competitor's item id (IDOR).
     */
    Optional<MenuItem> findByIdAndRestaurantIdAndActiveTrue(Long id, Long restaurantId);

    List<MenuItem> findByRestaurantIdAndActiveTrueOrderByCategoryAscNameAsc(Long restaurantId);

    @Query("""
            SELECT m FROM MenuItem m
            WHERE m.restaurant.id = :restaurantId
              AND m.active = true
              AND (:category IS NULL OR m.category = :category)
              AND (:vegOnly = false OR m.veg = true)
            ORDER BY m.category ASC, m.name ASC
            """)
    List<MenuItem> findPublicMenu(Long restaurantId, String category, boolean vegOnly);
}
