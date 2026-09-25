package com.fooddelivery.delivery;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.common.web.PageResponse;
import com.fooddelivery.order.Order;
import com.fooddelivery.order.OrderRepository;
import com.fooddelivery.order.OrderResponse;
import com.fooddelivery.order.OrderStatus;
import com.fooddelivery.payment.PaymentRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Partner availability and the claim race. Many partners may claim one order, and one partner may
 * tap several orders: both are decided by compare-and-set UPDATEs (hot contention on single rows).
 */
@Service
public class DeliveryAssignmentService {

    private static final Set<OrderStatus> CLAIMABLE =
            EnumSet.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, OrderStatus.READY_FOR_PICKUP);

    private final DeliveryPartnerRepository partnerRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;

    public DeliveryAssignmentService(DeliveryPartnerRepository partnerRepository, OrderRepository orderRepository,
                                     PaymentRepository paymentRepository) {
        this.partnerRepository = partnerRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
    }

    // ---- partner profile / availability ----

    @Transactional(readOnly = true)
    public DeliveryPartnerResponse me(Long userId) {
        return DeliveryPartnerResponse.from(requirePartner(userId));
    }

    /**
     * Entity update with @Version: if a claim (which bumps version) happens at the same moment,
     * one of the two fails with 409 instead of a partner going OFFLINE mid-delivery.
     */
    @Transactional
    public DeliveryPartnerResponse setAvailability(Long userId, PartnerStatus target) {
        if (target == PartnerStatus.BUSY) {
            throw new BadRequestException(ErrorCode.INVALID_PARTNER_STATUS,
                    "Status can be AVAILABLE or OFFLINE; BUSY is set when you claim an order");
        }
        DeliveryPartner partner = requirePartner(userId);
        if (partner.getStatus() == PartnerStatus.BUSY) {
            throw new ConflictException(ErrorCode.PARTNER_BUSY, "Finish your current delivery first");
        }
        partner.setStatus(target);
        return DeliveryPartnerResponse.from(partner);
    }

    // ---- orders ----

    @Transactional(readOnly = true)
    public PageResponse<AvailableOrderResponse> availableOrders(Long userId, int page, int size) {
        DeliveryPartner partner = requirePartner(userId);
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt", "id"));
        return PageResponse.from(orderRepository.findClaimableInCity(partner.getCity().getId(), pageable)
                .map(AvailableOrderResponse::from));
    }

    @Transactional
    public OrderResponse claim(Long orderId, Long userId) {
        DeliveryPartner partner = requirePartner(userId);
        Order order = orderRepository.findWithItemsById(orderId)
                .orElseThrow(() -> new NotFoundException("Order", orderId));
        // Other cities' orders are invisible to this partner.
        if (!order.getRestaurant().getCity().getId().equals(partner.getCity().getId())) {
            throw new NotFoundException("Order", orderId);
        }

        // Friendly early checks from this transaction's snapshot...
        if (partner.getStatus() != PartnerStatus.AVAILABLE) {
            throw new ConflictException(ErrorCode.PARTNER_NOT_AVAILABLE,
                    "You must be AVAILABLE (and not on another delivery) to claim an order");
        }
        if (!CLAIMABLE.contains(order.getStatus())) {
            throw new ConflictException(ErrorCode.ORDER_NOT_CLAIMABLE,
                    "Order " + orderId + " is " + order.getStatus() + " and cannot be claimed");
        }
        if (order.getDeliveryPartner() != null) {
            throw alreadyAssigned(orderId);
        }

        // ...then the real decision, atomically, PARTNER ROW FIRST. Writing orders.delivery_partner_id
        // makes the FK check take a shared lock on the partner row; if that came before markBusy's
        // exclusive lock, one partner claiming several orders at once would deadlock (S -> X upgrade,
        // found by PartnerClaimConcurrencyTest). Taking X first means the FK check hits our own lock.
        if (partnerRepository.markBusy(partner.getId()) == 0) {
            // This partner just claimed a different order in parallel.
            throw new ConflictException(ErrorCode.PARTNER_NOT_AVAILABLE, "You are already on another delivery");
        }
        if (orderRepository.claim(orderId, partner.getId()) == 0) {
            // Another partner won, or the order was cancelled meanwhile; markBusy rolls back with us.
            throw alreadyAssigned(orderId);
        }

        // claim() cleared the persistence context, so this reads the updated row.
        Order claimed = orderRepository.findWithItemsById(orderId).orElseThrow();
        return OrderResponse.from(claimed, paymentRepository.findByOrderId(orderId).orElse(null));
    }

    @Transactional(readOnly = true)
    public Optional<OrderResponse> currentOrder(Long userId) {
        DeliveryPartner partner = requirePartner(userId);
        return orderRepository.findActiveForPartner(partner.getId())
                .map(o -> OrderResponse.from(o, paymentRepository.findByOrderId(o.getId()).orElse(null)));
    }

    private DeliveryPartner requirePartner(Long userId) {
        return partnerRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Delivery partner profile for user", userId));
    }

    private static ConflictException alreadyAssigned(Long orderId) {
        return new ConflictException(ErrorCode.ORDER_ALREADY_ASSIGNED,
                "Order " + orderId + " was already claimed by another partner or is no longer available");
    }
}
