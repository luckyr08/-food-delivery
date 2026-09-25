package com.fooddelivery.restaurant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    // ---- admin ----

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

    // ---- owner ----

    @EntityGraph(attributePaths = {"owner", "city"})
    List<Restaurant> findByOwnerIdOrderByNameAsc(Long ownerId);

    /** Existence + ownership in one query: someone else's restaurant looks exactly like a missing one. */
    @EntityGraph(attributePaths = {"owner", "city"})
    Optional<Restaurant> findByIdAndOwnerId(Long id, Long ownerId);

    // ---- public browsing: only active restaurants in active cities ----

    /**
     * Uses the (city_id, active) index. Name search is a contains-LIKE (can't use an index, but is
     * bounded to one city); the ci collation makes it case-insensitive without LOWER().
     */
    @EntityGraph(attributePaths = {"city"})
    @Query("""
            SELECT r FROM Restaurant r
            WHERE r.city.id = :cityId
              AND r.city.active = true
              AND r.active = true
              AND (:openOnly = false OR r.open = true)
              AND (:cuisine IS NULL OR r.cuisine = :cuisine)
              AND (:namePattern IS NULL OR r.name LIKE :namePattern ESCAPE '\\')
            ORDER BY r.open DESC, r.name ASC, r.id ASC
            """)
    Page<Restaurant> browse(Long cityId, boolean openOnly, String cuisine, String namePattern, Pageable pageable);

    @EntityGraph(attributePaths = {"city"})
    @Query("SELECT r FROM Restaurant r WHERE r.id = :id AND r.active = true AND r.city.active = true")
    Optional<Restaurant> findPublicById(Long id);
}
