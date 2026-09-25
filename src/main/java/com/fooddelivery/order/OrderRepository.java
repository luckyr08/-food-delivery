package com.fooddelivery.order;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByCustomerIdAndIdempotencyKey(Long customerId, String idempotencyKey);

    /** Detail view: one order, so fetching the items collection here is fine. */
    @EntityGraph(attributePaths = {"restaurant", "items"})
    Optional<Order> findWithItemsById(Long id);

    /** Ownership built into the query: another customer's order looks like a missing one. */
    @EntityGraph(attributePaths = {"restaurant", "items"})
    Optional<Order> findWithItemsByIdAndCustomerId(Long id, Long customerId);

    /** List view: no items collection (it would force in-memory pagination). */
    @EntityGraph(attributePaths = {"restaurant"})
    Page<Order> findByCustomerId(Long customerId, Pageable pageable);
}
