package com.fooddelivery.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Atomic check-and-decrement: the oversell guard. InnoDB row-locks the item, re-reads the latest
     * committed row (a locking read, even under REPEATABLE READ), evaluates the WHERE and updates.
     * Returns 1 on success, 0 if the item is gone/unavailable or has too little stock.
     * Unlimited items (stock NULL) are matched and locked too, so a concurrent owner change can't slip
     * in between. version is bumped so an owner's concurrent entity update fails with 409.
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE menu_items
            SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock - :quantity END,
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE id = :id
              AND active = TRUE
              AND available = TRUE
              AND (stock IS NULL OR stock >= :quantity)
            """)
    int deductStock(Long id, int quantity);

    /** Returns stock of a cancelled/rejected order. Unlimited items (NULL) are left alone. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE menu_items
            SET stock = stock + :quantity,
                version = version + 1,
                updated_at = UTC_TIMESTAMP(6)
            WHERE id = :id
              AND stock IS NOT NULL
            """)
    int restock(Long id, int quantity);
}
