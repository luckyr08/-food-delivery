package com.fooddelivery.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, Long> {

    @EntityGraph(attributePaths = {"user", "city"})
    @Query("""
            SELECT p FROM DeliveryPartner p
            WHERE (:cityId IS NULL OR p.city.id = :cityId)
              AND (:status IS NULL OR p.status = :status)
            """)
    Page<DeliveryPartner> search(Long cityId, PartnerStatus status, Pageable pageable);

    @EntityGraph(attributePaths = {"user", "city"})
    Optional<DeliveryPartner> findWithUserAndCityById(Long id);
}
