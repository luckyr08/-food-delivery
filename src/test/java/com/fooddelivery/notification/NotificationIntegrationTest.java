package com.fooddelivery.notification;

import com.fooddelivery.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The external sender is a Mockito mock so tests can slow it down, make it fail, or record threads. */
class NotificationIntegrationTest extends IntegrationTestBase {

    @MockitoBean
    NotificationSender sender;

    String admin;
    String owner;
    String customer;
    Long restaurant;
    Long dal;
    Long ownerId;
    Long customerId;

    @BeforeEach
    void setUp() throws Exception {
        admin = adminToken();
        ownerId = createOwner(admin, "owner@example.com");
        restaurant = createRestaurant(admin, ownerId, createCity(admin, "Pune"), "Spice Hub");
        owner = login("owner@example.com", "secret123");
        openRestaurant(owner, restaurant);
        dal = createMenuItem(owner, restaurant, "Dal", "150.00", null);
        registerCustomer("cust@example.com", "secret123");
        customer = login("cust@example.com", "secret123");
        customerId = jdbc.queryForObject("SELECT id FROM users WHERE email = 'cust@example.com'", Long.class);
    }

    private Long placeOrder() throws Exception {
        return idFrom(postAs(customer, "/api/orders", """
                {"restaurantId":%d,"items":[{"menuItemId":%d,"quantity":1}],
                 "deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurant, dal)).andExpect(status().isCreated()), "$.id");
    }

    private int notificationsFor(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_user_id = ?",
                Integer.class, userId);
    }

    @Test
    void newOrderNotifiesRestaurantButNotTheCustomerWhoPlacedIt() throws Exception {
        Long order = placeOrder();

        await().atMost(Duration.ofSeconds(5)).until(() -> notificationsFor(ownerId) == 1);
        getAs(owner, "/api/notifications")
                .andExpect(jsonPath("$.content[0].message").value("New Order #" + order + " received"))
                .andExpect(jsonPath("$.content[0].orderId").value(order))
                .andExpect(jsonPath("$.content[0].read").value(false));
        awaitAsyncWork();
        assertThat(notificationsFor(customerId)).isZero();
    }

    @Test
    void restaurantAcceptingNotifiesTheCustomer() throws Exception {
        Long order = placeOrder();
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/orders/" + order + "/status",
                "{\"status\":\"ACCEPTED\"}").andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(5)).until(() -> notificationsFor(customerId) == 1);
        getAs(customer, "/api/notifications")
                .andExpect(jsonPath("$.content[0].message").value("Order #" + order + " from Spice Hub is now ACCEPTED"));
    }

    @Test
    void slowSenderDoesNotBlockTheRequest() throws Exception {
        doAnswer(inv -> {
            Thread.sleep(2000);
            return null;
        }).when(sender).send(anyLong(), anyString());

        long start = System.nanoTime();
        placeOrder();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).as("request must not wait for the 2 s sender").isLessThan(1000);
        verify(sender, timeout(5000)).send(eq(ownerId), anyString()); // but it does happen, later
    }

    @Test
    void fanOutRunsOnTheNotificationPool() throws Exception {
        List<String> threads = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            threads.add(Thread.currentThread().getName());
            return null;
        }).when(sender).send(anyLong(), anyString());

        placeOrder();

        await().atMost(Duration.ofSeconds(5)).until(() -> !threads.isEmpty());
        assertThat(threads).allMatch(name -> name.startsWith("notify-"));
    }

    @Test
    void oneFailingRecipientDoesNotStopTheOthers() throws Exception {
        Long order = placeOrder();
        patchAs(owner, "/api/owner/restaurants/" + restaurant + "/orders/" + order + "/status",
                "{\"status\":\"ACCEPTED\"}");
        String partner = createPartnerAndGoOnline();
        doThrow(new RuntimeException("push provider down")).when(sender).send(eq(customerId), anyString());

        postAs(partner, "/api/partner/orders/" + order + "/claim", "").andExpect(status().isOk());

        // PARTNER_ASSIGNED goes to customer (fails) then owner (must still happen)
        verify(sender, timeout(5000)).send(eq(ownerId), eq(
                "A delivery partner has been assigned to Order #" + order + " from Spice Hub"));
    }

    @Test
    void markReadAndReadAllOnlyTouchOwnNotifications() throws Exception {
        placeOrder();
        placeOrder();
        await().atMost(Duration.ofSeconds(5)).until(() -> notificationsFor(ownerId) == 2);
        Long first = jdbc.queryForObject(
                "SELECT MIN(id) FROM notifications WHERE recipient_user_id = ?", Long.class, ownerId);

        patchAs(customer, "/api/notifications/" + first + "/read", "").andExpect(status().isNotFound());
        patchAs(owner, "/api/notifications/" + first + "/read", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.read").value(true));
        getAs(owner, "/api/notifications?unreadOnly=true").andExpect(jsonPath("$.totalElements").value(1));

        postAs(owner, "/api/notifications/read-all", "").andExpect(jsonPath("$.updated").value(1));
        getAs(owner, "/api/notifications?unreadOnly=true").andExpect(jsonPath("$.totalElements").value(0));
    }

    private String createPartnerAndGoOnline() throws Exception {
        Long city = jdbc.queryForObject("SELECT city_id FROM restaurants WHERE id = ?", Long.class, restaurant);
        postAs(admin, "/api/admin/delivery-partners", """
                {"name":"Kiran","email":"kiran@example.com","password":"secret123","cityId":%d,"vehicleType":"SCOOTER"}
                """.formatted(city)).andExpect(status().isCreated());
        String partner = login("kiran@example.com", "secret123");
        patchAs(partner, "/api/partner/me/status", "{\"status\":\"AVAILABLE\"}").andExpect(status().isOk());
        return partner;
    }
}
