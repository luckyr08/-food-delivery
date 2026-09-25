package com.fooddelivery.rating;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    boolean existsByOrderId(Long orderId);

    Optional<Review> findByOrderId(Long orderId);

    /** Uses the (restaurant_id, created_at) index; loads the reviewer's name in the same query. */
    @EntityGraph(attributePaths = {"customer"})
    Page<Review> findByRestaurantId(Long restaurantId, Pageable pageable);
}
