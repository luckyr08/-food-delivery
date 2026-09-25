package com.fooddelivery.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Uses the (recipient_user_id, created_at) index. */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipient.id = :recipientId
              AND (:unreadOnly = false OR n.read = false)
            """)
    Page<Notification> findForRecipient(Long recipientId, boolean unreadOnly, Pageable pageable);

    Optional<Notification> findByIdAndRecipientId(Long id, Long recipientId);

    @Modifying
    @Query("UPDATE Notification n SET n.read = true WHERE n.recipient.id = :recipientId AND n.read = false")
    int markAllRead(Long recipientId);
}
