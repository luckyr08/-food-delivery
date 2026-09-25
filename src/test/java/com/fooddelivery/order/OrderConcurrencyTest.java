package com.fooddelivery.order;

import com.fooddelivery.security.AuthUser;
import com.fooddelivery.security.JwtService;
import com.fooddelivery.support.DatabaseCleaner;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real server on a random port, real HTTP from many threads, real MySQL. All threads wait on a latch
 * and fire at the same instant to maximise contention. Setup data goes straight in via JDBC and tokens
 * are minted with JwtService, so the only thing exercised concurrently is order placement.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class OrderConcurrencyTest {

    @LocalServerPort
    int port;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    JwtService jwtService;

    final HttpClient http = HttpClient.newHttpClient();
    Long restaurantId;

    @BeforeEach
    void setUp() {
        DatabaseCleaner.clean(jdbc);
        Long ownerId = insertUser("owner@example.com", "RESTAURANT_OWNER");
        Long cityId = insert("INSERT INTO cities (name, active, created_at, updated_at) "
                + "VALUES ('Pune', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        restaurantId = insert("INSERT INTO restaurants (owner_id, city_id, name, address, is_open, active, "
                + "created_at, updated_at) VALUES (" + ownerId + ", " + cityId
                + ", 'Spice Hub', 'MG Road', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    @Test
    void fiftyCustomersRaceForTenUnits_exactlyTenWin() throws Exception {
        Long biryani = insertMenuItem("Biryani", 10);
        List<String> tokens = customers(50);

        List<Integer> statuses = runConcurrently(tokens.size(), i ->
                placeOrder(tokens.get(i), "[{\"menuItemId\":" + biryani + ",\"quantity\":1}]", null).statusCode());

        Map<Integer, Long> byStatus = countByValue(statuses);
        assertThat(byStatus).containsEntry(201, 10L).containsEntry(409, 40L).hasSize(2);
        assertThat(stock(biryani)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(10);
    }

    @Test
    void oppositeItemOrderDoesNotDeadlock() throws Exception {
        Long a = insertMenuItem("A", 100);
        Long b = insertMenuItem("B", 100);
        List<String> tokens = customers(40);
        String ab = "[{\"menuItemId\":" + a + ",\"quantity\":1},{\"menuItemId\":" + b + ",\"quantity\":1}]";
        String ba = "[{\"menuItemId\":" + b + ",\"quantity\":1},{\"menuItemId\":" + a + ",\"quantity\":1}]";

        List<Integer> statuses = runConcurrently(tokens.size(), i ->
                placeOrder(tokens.get(i), i % 2 == 0 ? ab : ba, null).statusCode());

        assertThat(statuses).containsOnly(201);
        assertThat(stock(a)).isEqualTo(60);
        assertThat(stock(b)).isEqualTo(60);
    }

    @Test
    void sameIdempotencyKeyTenTimesAtOnce_oneOrder() throws Exception {
        Long biryani = insertMenuItem("Biryani", 10);
        String token = customers(1).get(0);
        String items = "[{\"menuItemId\":" + biryani + ",\"quantity\":1}]";

        List<HttpResponse<String>> responses = runConcurrently(10, i -> placeOrder(token, items, "race-key-001"));

        assertThat(responses).allSatisfy(r -> assertThat(r.statusCode()).isIn(200, 201));
        assertThat(responses.stream().filter(r -> r.statusCode() == 201)).hasSize(1);
        assertThat(responses.stream().map(r -> r.body().replaceAll(".*?\"id\":(\\d+).*", "$1")).distinct())
                .hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isEqualTo(1);
        assertThat(stock(biryani)).isEqualTo(9);
    }

    // ---- helpers ----

    private HttpResponse<String> placeOrder(String token, String itemsJson, String idempotencyKey) throws Exception {
        String body = """
                {"restaurantId":%d,"items":%s,"deliveryAddress":"Flat 4B, MG Road","paymentMethod":"UPI"}
                """.formatted(restaurantId, itemsJson);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/orders"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Starts n tasks that all block on one latch, releases them together, collects results. */
    private <T> List<T> runConcurrently(int n, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int index = i;
                futures.add(pool.submit((Callable<T>) () -> {
                    start.await();
                    return task.run(index);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    private List<String> customers(int n) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Long id = insertUser("customer" + i + "@example.com", "CUSTOMER");
            tokens.add(jwtService.issue(AuthUser.fromToken(id, Role.CUSTOMER)));
        }
        return tokens;
    }

    private Long insertUser(String email, String role) {
        return insert("INSERT INTO users (name, email, password_hash, role, active, created_at, updated_at) "
                + "VALUES ('Test', '" + email + "', 'x', '" + role + "', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    private Long insertMenuItem(String name, int stock) {
        return insert("INSERT INTO menu_items (restaurant_id, name, price, is_veg, available, stock, active, "
                + "created_at, updated_at) VALUES (" + restaurantId + ", '" + name
                + "', 300.00, TRUE, TRUE, " + stock + ", TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    private Long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    private Integer stock(Long itemId) {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, itemId);
    }

    private static <T> Map<T, Long> countByValue(List<T> values) {
        return values.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }
}
