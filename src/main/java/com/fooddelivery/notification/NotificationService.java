package com.fooddelivery.notification;

import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.order.OrderRepository;
import com.fooddelivery.user.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;

    public NotificationService(NotificationRepository notificationRepository, UserRepository userRepository,
                               OrderRepository orderRepository) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.orderRepository = orderRepository;
    }

    /** Own short transaction per recipient, so one failed insert doesn't undo the others. */
    @Transactional
    public void record(Long recipientId, Long orderId, NotificationType type, String message) {
        Notification n = new Notification();
        n.setRecipient(userRepository.getReferenceById(recipientId));
        n.setOrder(orderRepository.getReferenceById(orderId));
        n.setType(type);
        n.setMessage(message);
        notificationRepository.save(n);
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(Long userId, boolean unreadOnly, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(notificationRepository.findForRecipient(userId, unreadOnly, pageable)
                .map(NotificationResponse::from));
    }

    @Transactional
    public NotificationResponse markRead(Long id, Long userId) {
        Notification n = notificationRepository.findByIdAndRecipientId(id, userId)
                .orElseThrow(() -> new NotFoundException("Notification", id));
        n.setRead(true);
        return NotificationResponse.from(n);
    }

    @Transactional
    public int markAllRead(Long userId) {
        return notificationRepository.markAllRead(userId);
    }
}
