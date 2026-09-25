package com.fooddelivery.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OrderStatusHistoryRepository extends JpaRepository<OrderStatusHistory, Long> {

    List<OrderStatusHistory> findByOrderIdOrderByChangedAtAscIdAsc(Long orderId);

    /** When the order entered a status, e.g. DELIVERED (final states are entered once). */
    Optional<OrderStatusHistory> findFirstByOrderIdAndToStatus(Long orderId, OrderStatus toStatus);
}
