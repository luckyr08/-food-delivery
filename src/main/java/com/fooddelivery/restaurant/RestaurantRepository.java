package com.fooddelivery.restaurant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    /**
     * Optional filters: a null parameter disables its condition.
     * The entity graph loads owner + city in the same query (avoids N+1). Safe with pagination
     * because both are to-one joins; fetching a collection here would force in-memory paging.
     */
    @EntityGraph(attributePaths = {"owner", "city"})
    @Query("""
            SELECT r FROM Restaurant r
            WHERE (:cityId IS NULL OR r.city.id = :cityId)
              AND (:active IS NULL OR r.active = :active)
            """)
    Page<Restaurant> search(Long cityId, Boolean active, Pageable pageable);

    @EntityGraph(attributePaths = {"owner", "city"})
    Optional<Restaurant> findWithOwnerAndCityById(Long id);
}
