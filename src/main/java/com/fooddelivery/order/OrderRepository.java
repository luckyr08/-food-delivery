package com.fooddelivery.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    /** Detail view: one order, so fetching the items collection here is fine. */
    @EntityGraph(attributePaths = {"restaurant", "items", "deliveryPartner", "deliveryPartner.user"})
    Optional<Order> findWithItemsById(Long id);

    /** Ownership built into the query: another customer's order looks like a missing one. */
    @EntityGraph(attributePaths = {"restaurant", "items", "deliveryPartner", "deliveryPartner.user"})
    Optional<Order> findWithItemsByIdAndCustomerId(Long id, Long customerId);

    /** List view: no items collection (it would force in-memory pagination). */
    @EntityGraph(attributePaths = {"restaurant"})
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);

    /** Owner access: the order must belong to the (already ownership-checked) restaurant. */
    @EntityGraph(attributePaths = {"restaurant", "items", "deliveryPartner", "deliveryPartner.user"})
    Optional<Order> findWithItemsByIdAndRestaurantId(Long id, Long restaurantId);

    /** Partner access: only the assigned partner can see/update the order. */
    @EntityGraph(attributePaths = {"restaurant", "items", "deliveryPartner", "deliveryPartner.user"})
    Optional<Order> findWithItemsByIdAndDeliveryPartnerId(Long id, Long deliveryPartnerId);

    /** The partner's active delivery (at most one: a partner is BUSY with one order). */
    @EntityGraph(attributePaths = {"restaurant", "items", "deliveryPartner", "deliveryPartner.user"})
    @Query("""
            SELECT o FROM Order o
            WHERE o.deliveryPartner.id = :partnerId
              AND o.status NOT IN (com.fooddelivery.order.OrderStatus.DELIVERED,
                                   com.fooddelivery.order.OrderStatus.CANCELLED,
                                   com.fooddelivery.order.OrderStatus.REJECTED)
            """)
    Optional<Order> findActiveForPartner(Long partnerId);

    /** Claimable orders in a city; uses the (status, delivery_partner_id) index. */
    @EntityGraph(attributePaths = {"restaurant"})
    @Query("""
            SELECT o FROM Order o
            WHERE o.deliveryPartner IS NULL
              AND o.status IN (com.fooddelivery.order.OrderStatus.ACCEPTED,
                               com.fooddelivery.order.OrderStatus.PREPARING,
                               com.fooddelivery.order.OrderStatus.READY_FOR_PICKUP)
              AND o.restaurant.city.id = :cityId
            """)
    Page<Order> findClaimableInCity(Long cityId, Pageable pageable);

    /**
     * Compare-and-set claim: succeeds only if nobody has the order yet and it is still claimable.
     * Bumps version on purpose: Hibernate writes every column on an entity update, so without the bump
     * a concurrent owner status change (loaded before the claim) would write delivery_partner_id back
     * to NULL. With it, that owner update fails its @Version check (409) instead of erasing the claim.
     */
    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE orders
            SET delivery_partner_id = :partnerId, version = version + 1, updated_at = UTC_TIMESTAMP(6)
            WHERE id = :orderId
              AND delivery_partner_id IS NULL
              AND status IN ('ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP')
            """)
    int claim(Long orderId, Long partnerId);

    /** Restaurant's order queue; uses the (restaurant_id, status) index. */
    @EntityGraph(attributePaths = {"restaurant"})
    @Query("""
            SELECT o FROM Order o
            WHERE o.restaurant.id = :restaurantId
              AND (:status IS NULL OR o.status = :status)
            """)
    Page<Order> findForRestaurant(Long restaurantId, OrderStatus status, Pageable pageable);
}
