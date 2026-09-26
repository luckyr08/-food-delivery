package com.fooddelivery.rating;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.delivery.DeliveryPartnerRepository;
import com.fooddelivery.order.Order;
import com.fooddelivery.order.OrderRepository;
import com.fooddelivery.order.OrderStatus;
import com.fooddelivery.order.OrderStatusHistoryRepository;
import com.fooddelivery.outbox.OutboxWriter;
import com.fooddelivery.restaurant.RestaurantBrowseService;
import com.fooddelivery.restaurant.RestaurantRepository;
import com.fooddelivery.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final RestaurantRepository restaurantRepository;
    private final DeliveryPartnerRepository partnerRepository;
    private final RestaurantBrowseService restaurantBrowseService;
    private final UserRepository userRepository;
    private final ReviewProperties properties;
    private final Clock clock;
    private final OutboxWriter outboxWriter;

    public ReviewService(ReviewRepository reviewRepository, OrderRepository orderRepository,
                         OrderStatusHistoryRepository historyRepository, RestaurantRepository restaurantRepository,
                         DeliveryPartnerRepository partnerRepository, RestaurantBrowseService restaurantBrowseService,
                         UserRepository userRepository, ReviewProperties properties, Clock clock,
                         OutboxWriter outboxWriter) {
        this.reviewRepository = reviewRepository;
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.restaurantRepository = restaurantRepository;
        this.partnerRepository = partnerRepository;
        this.restaurantBrowseService = restaurantBrowseService;
        this.userRepository = userRepository;
        this.properties = properties;
        this.clock = clock;
        this.outboxWriter = outboxWriter;
    }

    /**
     * One transaction: aggregates and the review row commit together, so a rejected duplicate
     * never counts. Reviews are immutable once submitted.
     */
    @Transactional
    public ReviewResponse submit(Long orderId, Long customerId, ReviewRequest request) {
        Order order = orderRepository.findWithItemsByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new ConflictException(ErrorCode.ORDER_NOT_DELIVERED, "Only delivered orders can be reviewed");
        }
        requireWithinWindow(orderId);
        if (reviewRepository.existsByOrderId(orderId)) {
            throw alreadyReviewed();
        }
        if (request.partnerRating() != null && order.getDeliveryPartner() == null) {
            throw new BadRequestException(ErrorCode.NO_PARTNER_TO_RATE, "This order had no delivery partner");
        }

        // Aggregates FIRST: the review's FK to restaurants would take a shared lock on the restaurant row,
        // and concurrent reviews upgrading S -> X would deadlock (same rule as ADR 0008 / 0010).
        outboxWriter.restaurantChanged(order.getRestaurant().getId()); // rating is shown in search
        restaurantRepository.addRating(order.getRestaurant().getId(), request.restaurantRating());
        if (request.partnerRating() != null) {
            partnerRepository.addRating(order.getDeliveryPartner().getId(), request.partnerRating());
        }

        Review review = new Review();
        review.setOrder(order);
        review.setCustomer(userRepository.getReferenceById(customerId));
        review.setRestaurant(order.getRestaurant());
        review.setRestaurantRating(request.restaurantRating());
        review.setPartnerRating(request.partnerRating());
        review.setComment(request.comment());
        try {
            reviewRepository.save(review);
        } catch (DataIntegrityViolationException e) {
            // A concurrent duplicate won the UNIQUE(order_id) race; our increments roll back with us.
            throw alreadyReviewed();
        }
        return ReviewResponse.from(review);
    }

    @Transactional(readOnly = true)
    public ReviewResponse getForCustomer(Long orderId, Long customerId) {
        orderRepository.findWithItemsByIdAndCustomerId(orderId, customerId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        return reviewRepository.findByOrderId(orderId).map(ReviewResponse::from)
                .orElseThrow(() -> new NotFoundException("Review for order", orderId));
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicReviewResponse> listForRestaurant(Long restaurantId, int page, int size) {
        restaurantBrowseService.requirePublic(restaurantId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(reviewRepository.findByRestaurantId(restaurantId, pageable)
                .map(PublicReviewResponse::from));
    }

    private void requireWithinWindow(Long orderId) {
        Instant deliveredAt = historyRepository.findFirstByOrderIdAndToStatus(orderId, OrderStatus.DELIVERED)
                .orElseThrow(() -> new ConflictException(ErrorCode.ORDER_NOT_DELIVERED, "Delivery time unknown"))
                .getChangedAt();
        if (clock.instant().isAfter(deliveredAt.plus(Duration.ofDays(properties.windowDays())))) {
            throw new ConflictException(ErrorCode.REVIEW_WINDOW_CLOSED,
                    "Reviews are accepted up to " + properties.windowDays() + " days after delivery");
        }
    }

    private static ConflictException alreadyReviewed() {
        return new ConflictException(ErrorCode.ALREADY_REVIEWED, "This order has already been reviewed");
    }
}
