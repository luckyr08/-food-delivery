package com.fooddelivery.delivery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    @EntityGraph(attributePaths = {"user", "city"})
    Optional<DeliveryPartner> findByUserId(Long userId);

    /** Compare-and-set: only a currently AVAILABLE partner can become BUSY. 0 rows = lost the race. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE delivery_partners
            SET status = 'BUSY', version = version + 1, updated_at = UTC_TIMESTAMP(6)
            WHERE id = :id AND status = 'AVAILABLE'
            """)
    int markBusy(Long id);

    /** Frees a partner after delivery or when their order is cancelled. */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE delivery_partners
            SET status = 'AVAILABLE', version = version + 1, updated_at = UTC_TIMESTAMP(6)
            WHERE id = :id AND status = 'BUSY'
            """)
    int markAvailable(Long id);
}
