package com.fooddelivery.support;

import com.fooddelivery.outbox.OutboxRelay;
import com.fooddelivery.search.InMemorySearchIndex;
import com.fooddelivery.security.AuthUser;
import com.fooddelivery.security.JwtService;
import com.fooddelivery.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/**
 * Real server on a random port, real HTTP from many threads, real MySQL. All threads wait on a latch
 * and fire at the same instant to maximise contention. Setup data goes straight in via JDBC and tokens
 * are minted with JwtService, so the only thing exercised concurrently is the behaviour under test.
 * Base data: one city (Pune), one owner, one open restaurant.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class ConcurrencyTestBase {

    @LocalServerPort
    protected int port;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected InMemorySearchIndex searchIndex;
    @Autowired
    protected OutboxRelay outboxRelay;

    protected final HttpClient http = HttpClient.newHttpClient();
    protected Long cityId;
    protected Long restaurantId;
    protected String ownerToken;

    @Autowired
    @Qualifier("notificationExecutor")
    protected ThreadPoolTaskExecutor notificationExecutor;

    @AfterEach
    protected void awaitAsyncWork() {
        AsyncTestSupport.awaitIdle(notificationExecutor);
    }

    @BeforeEach
    void setUpBaseData() {
        DatabaseCleaner.clean(jdbc);
        searchIndex.reset();
        Long ownerId = insertUser("owner@example.com", "RESTAURANT_OWNER");
        ownerToken = token(ownerId, Role.RESTAURANT_OWNER);
        cityId = insert("INSERT INTO cities (name, active, created_at, updated_at) "
                + "VALUES ('Pune', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        restaurantId = insert("INSERT INTO restaurants (owner_id, city_id, name, address, is_open, active, "
                + "created_at, updated_at) VALUES (" + ownerId + ", " + cityId
                + ", 'Spice Hub', 'MG Road', TRUE, TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    // ---- HTTP ----

    protected HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    protected HttpResponse<String> placeOrder(String token, String itemsJson, String idempotencyKey)
            throws Exception {
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

    protected static long idOf(String json) {
        return Long.parseLong(json.replaceAll(".*?\"id\":(\\d+).*", "$1"));
    }

    // ---- concurrency ----

    /** Starts n tasks that all block on one latch, releases them together, collects results. */
    protected <T> List<T> runConcurrently(int n, IndexedTask<T> task) throws Exception {
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
    protected interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    protected static <T> Map<T, Long> countByValue(List<T> values) {
        return values.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    // ---- data ----

    protected String token(Long userId, Role role) {
        return jwtService.issue(AuthUser.fromToken(userId, role));
    }

    protected List<String> customers(int n) {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tokens.add(token(insertUser("customer" + i + "@example.com", "CUSTOMER"), Role.CUSTOMER));
        }
        return tokens;
    }

    protected Long insertUser(String email, String role) {
        return insert("INSERT INTO users (name, email, password_hash, role, active, created_at, updated_at) "
                + "VALUES ('Test', '" + email + "', 'x', '" + role + "', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    /** Creates a partner user + profile in the base city; returns the partner's token. */
    protected String insertAvailablePartner(String email) {
        Long userId = insertUser(email, "DELIVERY_PARTNER");
        insert("INSERT INTO delivery_partners (user_id, city_id, vehicle_type, status, created_at, updated_at) "
                + "VALUES (" + userId + ", " + cityId + ", 'SCOOTER', 'AVAILABLE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        return token(userId, Role.DELIVERY_PARTNER);
    }

    protected Long insertMenuItem(String name, int stock) {
        return insert("INSERT INTO menu_items (restaurant_id, name, price, is_veg, available, stock, active, "
                + "created_at, updated_at) VALUES (" + restaurantId + ", '" + name
                + "', 300.00, TRUE, TRUE, " + stock + ", TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    protected Long insert(String sql) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS), keys);
        return keys.getKey().longValue();
    }

    protected Integer stock(Long itemId) {
        return jdbc.queryForObject("SELECT stock FROM menu_items WHERE id = ?", Integer.class, itemId);
    }
}
