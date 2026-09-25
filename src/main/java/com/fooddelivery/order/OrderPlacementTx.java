package com.fooddelivery.order;

import com.fooddelivery.common.error.BadRequestException;
import com.fooddelivery.common.error.ConflictException;
import com.fooddelivery.common.error.ErrorCode;
import com.fooddelivery.common.error.NotFoundException;
import com.fooddelivery.menu.MenuItem;
import com.fooddelivery.menu.MenuItemRepository;
import com.fooddelivery.payment.Payment;
import com.fooddelivery.payment.PaymentRepository;
import com.fooddelivery.payment.PaymentService;
import com.fooddelivery.restaurant.Restaurant;
import com.fooddelivery.restaurant.RestaurantBrowseService;
import com.fooddelivery.user.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The single transaction that places an order: stock, order and payment commit together or not at all.
 * Called only through OrderService, which adds deadlock retry and idempotency-race handling OUTSIDE
 * this transaction (retrying inside a rolled-back transaction would be useless).
 */
@Service
class OrderPlacementTx {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final MenuItemRepository menuItemRepository;
    private final PaymentRepository paymentRepository;
    private final RestaurantBrowseService restaurantBrowseService;
    private final UserRepository userRepository;
    private final PaymentService paymentService;
    private final OrderProperties orderProperties;

    OrderPlacementTx(OrderRepository orderRepository, OrderStatusHistoryRepository historyRepository,
                     MenuItemRepository menuItemRepository, PaymentRepository paymentRepository,
                     RestaurantBrowseService restaurantBrowseService, UserRepository userRepository,
                     PaymentService paymentService, OrderProperties orderProperties) {
        this.orderRepository = orderRepository;
        this.historyRepository = historyRepository;
        this.menuItemRepository = menuItemRepository;
        this.paymentRepository = paymentRepository;
        this.restaurantBrowseService = restaurantBrowseService;
        this.userRepository = userRepository;
        this.paymentService = paymentService;
        this.orderProperties = orderProperties;
    }

    @Transactional
    public PlacementResult place(Long customerId, PlaceOrderRequest request, String idempotencyKey,
                                 String requestHash) {
        // 1. Idempotent replay (sequential retries). Concurrent duplicates are caught by the UNIQUE key.
        if (idempotencyKey != null) {
            var existing = orderRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey);
            if (existing.isPresent()) {
                return replay(existing.get(), requestHash);
            }
        }

        // 2. Request-level rules that Bean Validation can't express.
        rejectDuplicateLines(request.items());

        // 3. Restaurant must be visible and open.
        Restaurant restaurant = restaurantBrowseService.requirePublic(request.restaurantId());
        if (!restaurant.isOpen()) {
            throw new ConflictException(ErrorCode.RESTAURANT_CLOSED,
                    "Restaurant " + restaurant.getId() + " is not accepting orders right now");
        }

        // 4. Items: exist, belong to this restaurant, currently switched on. (Friendly early checks;
        //    the atomic UPDATE in step 7 is the real guard.)
        Map<Long, MenuItem> menu = loadItems(request, restaurant.getId());

        // 5. Price from the database, never from the client.
        var totals = OrderPricing.calculate(request.items().stream()
                .map(l -> new OrderPricing.Line(menu.get(l.menuItemId()).getPrice(), l.quantity()))
                .toList(), orderProperties);

        // 6. Insert the order row first (no items yet): this claims the idempotency key. A concurrent
        //    duplicate blocks on the UNIQUE index here and then fails, before it ever touches stock.
        //    Its foreign keys only S-lock restaurants/users, never menu_items.
        Order order = new Order();
        order.setCustomer(userRepository.getReferenceById(customerId)); // proxy, no SELECT
        order.setRestaurant(restaurant);
        order.setStatus(OrderStatus.PLACED);
        order.setSubtotal(totals.subtotal());
        order.setDeliveryFee(totals.deliveryFee());
        order.setTotalAmount(totals.total());
        order.setDeliveryAddress(request.deliveryAddress());
        order.setIdempotencyKey(idempotencyKey);
        order.setIdempotencyRequestHash(idempotencyKey == null ? null : requestHash);
        orderRepository.save(order);

        // 7. Deduct stock atomically, in ascending id order so concurrent multi-item orders always
        //    lock rows in the same order (no lock cycles).
        //    This MUST happen before order_items are inserted: their FK to menu_items takes a shared
        //    lock on the item row, and two transactions both holding S and then asking for X is a
        //    deadlock (found by OrderConcurrencyTest). Taking X first means the later FK check hits a
        //    lock we already own.
        request.items().stream()
                .sorted(Comparator.comparing(OrderLineRequest::menuItemId))
                .forEach(line -> {
                    if (menuItemRepository.deductStock(line.menuItemId(), line.quantity()) == 0) {
                        // No exact count in the message: under REPEATABLE READ a plain SELECT here would
                        // read this transaction's snapshot, not the value the UPDATE just saw.
                        throw new ConflictException(ErrorCode.INSUFFICIENT_STOCK, "'"
                                + menu.get(line.menuItemId()).getName()
                                + "' is out of stock or unavailable in the requested quantity");
                    }
                });

        // 7b. Now the line items (cascaded from the order), with name/price snapshots.
        for (OrderLineRequest line : request.items()) {
            MenuItem item = menu.get(line.menuItemId());
            OrderItem orderItem = new OrderItem();
            orderItem.setMenuItem(item);
            orderItem.setItemName(item.getName());     // snapshot
            orderItem.setUnitPrice(item.getPrice());   // snapshot
            orderItem.setQuantity(line.quantity());
            order.addItem(orderItem);
        }
        orderRepository.flush(); // insert items now, while we hold the item row locks
        recordInitialStatus(order, customerId);

        // 8 + 9. Charge last, once nothing else can fail for business reasons.
        Payment payment = paymentService.charge(order, request.paymentMethod());

        return new PlacementResult(OrderResponse.from(order, payment), false);
    }

    /** Used by OrderService after a concurrent duplicate lost the UNIQUE-key race. */
    @Transactional(readOnly = true)
    public PlacementResult replay(Long customerId, String idempotencyKey, String requestHash) {
        Order order = orderRepository.findByCustomerIdAndIdempotencyKey(customerId, idempotencyKey)
                .orElseThrow(() -> new NotFoundException("Order with idempotency key", idempotencyKey));
        return replay(order, requestHash);
    }

    private PlacementResult replay(Order order, String requestHash) {
        if (!Objects.equals(order.getIdempotencyRequestHash(), requestHash)) {
            throw new ConflictException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different order request");
        }
        Order full = orderRepository.findWithItemsById(order.getId()).orElseThrow();
        return new PlacementResult(
                OrderResponse.from(full, paymentRepository.findByOrderId(full.getId()).orElse(null)), true);
    }

    private static void rejectDuplicateLines(List<OrderLineRequest> lines) {
        var seen = new HashSet<Long>();
        for (OrderLineRequest line : lines) {
            if (!seen.add(line.menuItemId())) {
                throw new BadRequestException(ErrorCode.DUPLICATE_ORDER_ITEM,
                        "Menu item " + line.menuItemId() + " appears more than once; combine the quantities");
            }
        }
    }

    private Map<Long, MenuItem> loadItems(PlaceOrderRequest request, Long restaurantId) {
        List<Long> ids = request.items().stream().map(OrderLineRequest::menuItemId).toList();
        Map<Long, MenuItem> byId = menuItemRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MenuItem::getId, Function.identity()));
        for (Long id : ids) {
            MenuItem item = byId.get(id);
            // getRestaurant().getId() reads the FK from the proxy: no extra query.
            if (item == null || !item.isActive() || !item.getRestaurant().getId().equals(restaurantId)) {
                throw new BadRequestException(ErrorCode.ITEM_NOT_IN_RESTAURANT,
                        "Menu item " + id + " is not on this restaurant's menu");
            }
            if (!item.isAvailable()) {
                throw new ConflictException(ErrorCode.ITEM_UNAVAILABLE, "'" + item.getName() + "' is unavailable");
            }
        }
        return byId;
    }

    private void recordInitialStatus(Order order, Long customerId) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrder(order);
        history.setFromStatus(null);
        history.setToStatus(OrderStatus.PLACED);
        history.setChangedBy(userRepository.getReferenceById(customerId));
        historyRepository.save(history);
    }
}
