package com.example.platform;

import com.example.gateway.MockGatewayServer;
import com.example.platform.payment.*;
import com.example.platform.order.application.CheckoutService;
import com.example.platform.shared.security.JwtService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"payment.gateway=http", "payment.http.connect-timeout=50ms",
        "payment.http.attempt-timeout=250ms", "payment.http.overall-timeout=550ms", "payment.http.retry-delay=10ms"})
@AutoConfigureMockMvc
@DirtiesContext
class HttpPaymentWorkflowIntegrationTest {
    static final AtomicReference<PaymentGateway> ledger = new AtomicReference<>();
    static final AtomicLong delay = new AtomicLong();
    static MockGatewayServer server;
    @DynamicPropertySource static void infra(DynamicPropertyRegistry registry) throws Exception {
        Phase6IntegrationTest.database(registry);
        ExtractedInventory.configure(registry);
        server = new MockGatewayServer(new InetSocketAddress("127.0.0.1", 0), (key, amount, currency) -> {
            var result = ledger.get().charge(key, amount, currency);
            try { Thread.sleep(delay.get()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return result;
        }, MockGatewayServer.Fault.NONE, 0);
        registry.add("payment.http.url", () -> "http://127.0.0.1:" + server.port() + "/charges");
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired PaymentService payments;
    @Autowired PaymentGateway gateway;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;

    @BeforeEach void setup() { ledger.set(new MockPaymentGateway(jdbc, PaymentGateway.Outcome.SUCCESS)); delay.set(0); breakers.circuitBreaker("paymentGateway").reset(); }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    @AfterAll static void close() { server.close(); }

    @Test void timeoutAfterProviderChargeKeepsStockAndReconcilesOneCharge() throws Exception {
        var f = fixture(); delay.set(1000);
        assertThat(gateway).isInstanceOf(HttpPaymentGateway.class);
        String token = jwt.issue(f.owner(), "CUSTOMER");
        mvc.perform(post("/api/orders/" + f.number() + "/payment").header("Authorization", "Bearer " + token))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.orderStatus").value("RESERVED"));
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT reserved_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_gateway_charges WHERE idempotency_key = ?", Integer.class, "order:" + f.id())).isEqualTo(1);
        delay.set(0);
        mvc.perform(post("/api/orders/" + f.number() + "/payment").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, f.id())).isEqualTo(1);
    }

    @Test void explicitHttpDeclineCompensatesAndMetricsAreAdminOnly() throws Exception {
        var f = fixture(); ledger.set(new MockPaymentGateway(jdbc, PaymentGateway.Outcome.DECLINED));
        assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CANCELLED");
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT available_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(5);
        mvc.perform(get("/actuator/metrics/payment.gateway.attempts").header("Authorization", "Bearer " + jwt.issue(f.owner(), "CUSTOMER"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/metrics/payment.gateway.attempts").header("Authorization", "Bearer " + jwt.issue(f.owner(), "ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics/resilience4j.circuitbreaker.state").header("Authorization", "Bearer " + jwt.issue(f.owner(), "ADMIN"))).andExpect(status().isOk());
    }

    @Test void openCircuitKeepsOrderPendingWithoutCallingProvider() {
        var f = fixture(); breakers.circuitBreaker("paymentGateway").transitionToOpenState();
        assertThat(payments.pay(f.owner(), f.number()).failureReason()).contains("CIRCUIT_OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_gateway_charges WHERE idempotency_key = ?", Integer.class, "order:" + f.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, f.id())).isEqualTo("RESERVED");
        breakers.circuitBreaker("paymentGateway").reset();
        assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CONFIRMED");
    }

    Fixture fixture() {
        UUID owner = UUID.randomUUID(), product = UUID.randomUUID(), cart = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'test')", owner, owner + "@example.com");
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, 'Item', 12.50, 'USD')", product, product.toString());
        ExtractedInventory.jdbc().update("INSERT INTO inventory(product_id, available_quantity, reserved_quantity) VALUES (?, 5, 0)", product);
        jdbc.update("INSERT INTO carts(id, customer_id) VALUES (?, ?)", cart, owner);
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, 2)", UUID.randomUUID(), cart, product);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
        var order = checkout.checkout(owner, UUID.randomUUID().toString());
        return new Fixture(owner, product, order.id(), order.orderNumber());
    }
    record Fixture(UUID owner, UUID product, UUID id, String number) { }
}
