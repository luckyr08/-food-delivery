package com.fooddelivery.city;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CityRepository extends JpaRepository<City, Long> {

    /** Case-insensitive: the column collation (utf8mb4_0900_ai_ci) makes "Pune" = "pune". */
    boolean existsByName(String name);

    boolean existsByNameAndIdNot(String name, Long id);

    List<City> findAllByOrderByNameAsc();

    List<City> findByActiveTrueOrderByNameAsc();
}
