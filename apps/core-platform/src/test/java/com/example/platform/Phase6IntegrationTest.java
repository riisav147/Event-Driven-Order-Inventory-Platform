package com.example.platform;

import com.example.platform.order.application.CheckoutService;
import com.example.platform.order.api.OrderDtos.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.example.platform.shared.security.JwtService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class Phase6IntegrationTest {
    private static PostgreSQLContainer<?> postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("phase6.jdbc-url");
        if (url == null) {
            postgres = new PostgreSQLContainer<>("postgres:16-alpine");
            postgres.start();
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        } else {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> System.getProperty("phase6.db-user", "platform"));
            registry.add("spring.datasource.password", () -> System.getProperty("phase6.db-password", ""));
        }
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @Test void httpRequiresKeyAndReplaysExactResponse() throws Exception {
        Fixture f = fixture();
        String token = jwt.issue(f.owner(), "CUSTOMER");
        mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                .header("X-Customer-Id", f.owner())).andExpect(status().isBadRequest());
        String first = mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                .header("X-Customer-Id", f.owner()).header("Idempotency-Key", "http"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String replay = mvc.perform(post("/api/orders").header("Authorization", "Bearer " + token)
                .header("X-Customer-Id", f.owner()).header("Idempotency-Key", "http"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(first);
    }

    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void concurrentRetriesReturnOneOrderAndOriginalResponse() throws Exception {
        Fixture f = fixture();
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<OrderResponse>> calls = IntStream.range(0, 40).mapToObj(i ->
                    (Callable<OrderResponse>) () -> {
                        authenticate(f.owner());
                        try { return checkout.checkout(f.owner(), "same-command"); }
                        finally { SecurityContextHolder.clearContext(); }
                    }).toList();
            var futures = executor.invokeAll(calls, 30, TimeUnit.SECONDS);
            OrderResponse first = futures.getFirst().get();
            for (var result : futures) assertThat(result.get()).isEqualTo(first);
            assertThat(count("orders", "customer_id", f.owner())).isEqualTo(1);
            assertThat(count("cart_items", "cart_id", f.cart())).isZero();
            jdbc.update("UPDATE orders SET status = 'CONFIRMED' WHERE id = ?", first.id());
            authenticate(f.owner());
            assertThat(checkout.checkout(f.owner(), "same-command")).isEqualTo(first);
        }
    }

    @Test void failedCheckoutRollsBackClaimAndCanRetry() {
        Fixture f = fixture();
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", f.cart());
        authenticate(f.owner());
        assertThatThrownBy(() -> checkout.checkout(f.owner(), "retry")).isInstanceOf(IllegalStateException.class);
        assertThat(count("idempotency_keys", "owner_id", f.owner())).isZero();
        addItem(f);
        assertThat(checkout.checkout(f.owner(), "retry").status()).isEqualTo("CREATED");
    }

    @Test void expiredAndMismatchedKeysNeverCreateAnotherOrder() {
        Fixture f = fixture();
        authenticate(f.owner());
        checkout.checkout(f.owner(), "key");
        jdbc.update("UPDATE idempotency_keys SET request_hash = 'different' WHERE owner_id = ?", f.owner());
        assertThatThrownBy(() -> checkout.checkout(f.owner(), "key")).hasMessageContaining("mismatch");
        assertThat(count("orders", "customer_id", f.owner())).isEqualTo(1);
    }

    @Test void expiredKeyIsRejected() {
        Fixture f = fixture();
        authenticate(f.owner());
        checkout.checkout(f.owner(), "key");
        jdbc.update("UPDATE idempotency_keys SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 day' WHERE owner_id = ?", f.owner());
        assertThatThrownBy(() -> checkout.checkout(f.owner(), "key")).hasMessageContaining("expired");
        assertThat(count("orders", "customer_id", f.owner())).isEqualTo(1);
    }

    @Test void disabledProductIsNotSilentlyDroppedFromCheckout() {
        Fixture f = fixture();
        authenticate(f.owner());
        jdbc.update("UPDATE products SET enabled = false WHERE id = ?", f.product());
        assertThatThrownBy(() -> checkout.checkout(f.owner(), "disabled")).hasMessageContaining("disabled");
        assertThat(count("orders", "customer_id", f.owner())).isZero();
        assertThat(count("cart_items", "cart_id", f.cart())).isEqualTo(1);
    }

    @Test void mixedCurrenciesCannotBecomeOnePaymentAmount() {
        Fixture f = fixture();
        UUID other = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, 'Other', 10, 'EUR')", other, other.toString());
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, 1)", UUID.randomUUID(), f.cart(), other);
        authenticate(f.owner());
        assertThatThrownBy(() -> checkout.checkout(f.owner(), "mixed")).hasMessageContaining("mixed currencies");
        assertThat(count("orders", "customer_id", f.owner())).isZero();
        assertThat(count("cart_items", "cart_id", f.cart())).isEqualTo(2);
    }

    @Test void sameKeyIsScopedToOwnerAndCannotBeReplayedByAnotherUser() {
        Fixture a = fixture();
        Fixture b = fixture();
        authenticate(a.owner());
        var first = checkout.checkout(a.owner(), "key");
        authenticate(b.owner());
        assertThatThrownBy(() -> checkout.checkout(a.owner(), "key"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(checkout.checkout(b.owner(), "key").id()).isNotEqualTo(first.id());
    }

    private Fixture fixture() {
        Fixture f = new Fixture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'test-only')", f.owner(), f.owner() + "@example.com");
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, 'Item', 12.50, 'USD')", f.product(), f.product().toString());
        jdbc.update("INSERT INTO carts(id, customer_id) VALUES (?, ?)", f.cart(), f.owner());
        addItem(f);
        return f;
    }
    private void addItem(Fixture f) {
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, 2)", UUID.randomUUID(), f.cart(), f.product());
    }
    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }
    private static void authenticate(UUID owner) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
    }
    private record Fixture(UUID owner, UUID cart, UUID product) { }
}
