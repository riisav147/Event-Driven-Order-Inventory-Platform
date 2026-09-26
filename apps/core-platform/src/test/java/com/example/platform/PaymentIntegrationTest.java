package com.example.platform;

import com.example.platform.order.application.CheckoutService;
import com.example.platform.order.api.OrderDtos.OrderResponse;
import com.example.platform.payment.*;
import com.example.platform.shared.security.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.example.platform.payment.PaymentGateway.Outcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "payment.gateway=local")
@AutoConfigureMockMvc
class PaymentIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        Phase6IntegrationTest.database(registry);
        ExtractedInventory.configure(registry);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired CheckoutService checkout;
    @Autowired PaymentService payments;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean PaymentGateway gateway;

    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }

    @Test void successConfirmsAndReplaysWithoutChargingAgain() {
        Fixture f = fixture(5);
        scenario(SUCCESS);
        var first = payments.pay(f.owner(), f.order().orderNumber());
        assertThat(first.orderStatus()).isEqualTo("CONFIRMED");
        assertThat(first.paymentStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?", String.class, f.order().id())).isEqualTo("PENDING");
        assertThat(first.gatewayReference()).startsWith("mock-");
        assertThat(payments.pay(f.owner(), f.order().orderNumber())).isEqualTo(first);
        verify(gateway, times(1)).charge(anyString(), any(), anyString());
        stock(f, 3, 0, "CONFIRMED");
        history(f, "CREATED", "RESERVED", "PAID", "CONFIRMED");
        assertThat(count("payment_attempts", "order_id", f.order().id())).isEqualTo(1);
    }

    @ParameterizedTest @EnumSource(value = PaymentGateway.Outcome.class, names = {"DECLINED", "ERROR"})
    void knownFailuresCompensateExactlyOnce(PaymentGateway.Outcome outcome) {
        Fixture f = fixture(5);
        scenario(outcome);
        var first = payments.pay(f.owner(), f.order().orderNumber());
        assertThat(first.orderStatus()).isEqualTo("CANCELLED");
        assertThat(first.paymentStatus()).isEqualTo(outcome.name());
        assertThat(first.failureReason()).isNotBlank();
        assertThat(payments.pay(f.owner(), f.order().orderNumber())).isEqualTo(first);
        stock(f, 5, 0, "RELEASED");
        history(f, "CREATED", "RESERVED", "PAYMENT_FAILED", "CANCELLED");
    }

    @Test void timeoutKeepsStockThenReconcilesSameProviderCharge() {
        Fixture f = fixture(5);
        scenario(TIMEOUT);
        var unknown = payments.pay(f.owner(), f.order().orderNumber());
        assertThat(unknown.paymentStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.orderStatus()).isEqualTo("RESERVED");
        stock(f, 3, 2, "RESERVED");
        // A fresh adapter instance represents a restart: the provider ledger survives it.
        scenario(TIMEOUT);
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).paymentStatus()).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_gateway_charges WHERE idempotency_key = ?",
                Integer.class, "order:" + f.order().id())).isEqualTo(1);
        stock(f, 3, 0, "CONFIRMED");
    }

    @Test void transportFailureAfterChargeDoesNotReleaseStock() {
        Fixture f = fixture(5);
        var provider = new MockPaymentGateway(jdbc, SUCCESS);
        when(gateway.charge(anyString(), any(), anyString())).thenAnswer(call -> {
            provider.charge(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            throw new IllegalStateException("connection lost after commit");
        });
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).paymentStatus()).isEqualTo("UNKNOWN");
        stock(f, 3, 2, "RESERVED");
        scenario(SUCCESS);
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).orderStatus()).isEqualTo("CONFIRMED");
    }

    @Test void concurrentRequestsHaveOneIntentOneChargeAndOneStockMovement() throws Exception {
        Fixture f = fixture(5);
        scenario(TIMEOUT);
        try (var executor = Executors.newFixedThreadPool(10)) {
            List<Callable<PaymentService.PaymentResponse>> calls = IntStream.range(0, 30).mapToObj(i ->
                    (Callable<PaymentService.PaymentResponse>) () -> {
                        authenticate(f.owner());
                        try { return payments.pay(f.owner(), f.order().orderNumber()); }
                        finally { SecurityContextHolder.clearContext(); }
                    }).toList();
            for (var result : executor.invokeAll(calls, 30, TimeUnit.SECONDS)) {
                assertThat(result.get().paymentStatus()).isIn("SUCCESS", "UNKNOWN", "PENDING");
            }
        }
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).paymentStatus()).isEqualTo("SUCCESS");
        assertThat(count("payment_attempts", "order_id", f.order().id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM mock_gateway_charges WHERE idempotency_key = ?",
                Integer.class, "order:" + f.order().id())).isEqualTo(1);
        stock(f, 3, 0, "CONFIRMED");
        history(f, "CREATED", "RESERVED", "PAID", "CONFIRMED");
    }

    @Test void insufficientStockRecordsDefinitiveRejectionBeforeGateway() {
        Fixture f = fixture(1);
        var result = payments.pay(f.owner(), f.order().orderNumber());
        assertThat(result.orderStatus()).isEqualTo("CANCELLED");
        assertThat(result.sagaPhase()).isEqualTo("DONE");
        verifyNoInteractions(gateway);
        assertThat(count("payment_attempts", "order_id", f.order().id())).isEqualTo(1);
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, f.order().id())).isZero();
        history(f, "CREATED", "CANCELLED");
    }

    @Test void laterLineFailureRollsBackEarlierStockReservation() {
        Fixture f = fixture(5);
        UUID laterProduct = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, 'LAST-PAYMENT-ITEM', 'Last', 0, 'USD') "
                + "ON CONFLICT (id) DO NOTHING", laterProduct);
        jdbc.update("INSERT INTO order_items(id, order_id, product_id, sku, name, unit_price, quantity) "
                + "VALUES (?, ?, ?, 'LAST-PAYMENT-ITEM', 'Last', 0, 1)", UUID.randomUUID(), f.order().id(), laterProduct);
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).orderStatus()).isEqualTo("CANCELLED");
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT available_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(5);
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT reserved_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isZero();
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, f.order().id())).isZero();
        assertThat(count("payment_attempts", "order_id", f.order().id())).isEqualTo(1);
        verifyNoInteractions(gateway);
    }

    @Test void lateTimeoutCannotOverwriteSuccessfulConcurrentFinalization() throws Exception {
        Fixture f = fixture(5);
        var provider = new MockPaymentGateway(jdbc, TIMEOUT);
        var firstCharged = new java.util.concurrent.CountDownLatch(1);
        var releaseTimeout = new java.util.concurrent.CountDownLatch(1);
        doAnswer(call -> {
            var result = provider.charge(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            if (result.outcome() == TIMEOUT) {
                firstCharged.countDown();
                assertThat(releaseTimeout.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(gateway).charge(anyString(), any(), anyString());
        try (var executor = Executors.newSingleThreadExecutor()) {
            var delayed = executor.submit(() -> {
                authenticate(f.owner());
                try { return payments.pay(f.owner(), f.order().orderNumber()); }
                finally { SecurityContextHolder.clearContext(); }
            });
            try {
                assertThat(firstCharged.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(payments.pay(f.owner(), f.order().orderNumber()).paymentStatus()).isEqualTo("SUCCESS");
            } finally { releaseTimeout.countDown(); }
            assertThat(delayed.get(10, TimeUnit.SECONDS).paymentStatus()).isEqualTo("SUCCESS");
        }
        stock(f, 3, 0, "CONFIRMED");
    }

    @Test void reservationConflictSurfacesPaidButUnconfirmedForRecovery() {
        Fixture f = fixture(5);
        var provider = new MockPaymentGateway(jdbc, SUCCESS);
        when(gateway.charge(anyString(), any(), anyString())).thenAnswer(call -> {
            var result = provider.charge(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            ExtractedInventory.jdbc().update("UPDATE reservation_orders SET status = 'RELEASED' WHERE order_id = ?", f.order().id());
            return result;
        });
        var pending = payments.pay(f.owner(), f.order().orderNumber());
        assertThat(pending.sagaPhase()).isEqualTo("CONFIRMING");
        assertThat(pending.paymentStatus()).isEqualTo("SUCCESS");
        history(f, "CREATED", "RESERVED", "PAID");
        ExtractedInventory.jdbc().update("UPDATE reservation_orders SET status = 'RESERVED' WHERE order_id = ?", f.order().id());
        assertThat(payments.pay(f.owner(), f.order().orderNumber()).orderStatus()).isEqualTo("CONFIRMED");
        stock(f, 3, 0, "CONFIRMED");
    }

    @Test void endpointUsesTokenOwnershipAndReturnsAcceptedForUnknown() throws Exception {
        Fixture f = fixture(5);
        scenario(TIMEOUT);
        String path = "/api/orders/" + f.order().orderNumber() + "/payment";
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt.issue(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(post(path)).andExpect(status().is4xxClientError());
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt.issue(f.owner(), "ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(gateway);
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt.issue(f.owner(), "CUSTOMER")))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.paymentStatus").value("UNKNOWN"));
        mvc.perform(post(path).header("Authorization", "Bearer " + jwt.issue(f.owner(), "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orderStatus").value("CONFIRMED"));
        authenticate(UUID.randomUUID());
        assertThatThrownBy(() -> payments.pay(f.owner(), f.order().orderNumber()))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    @Test void databaseAndGatewayRejectAmountMismatch() {
        Fixture f = fixture(5);
        scenario(SUCCESS);
        payments.pay(f.owner(), f.order().orderNumber());
        assertThatThrownBy(() -> jdbc.update("UPDATE payment_attempts SET amount = 1 WHERE order_id = ?", f.order().id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(() -> new MockPaymentGateway(jdbc, SUCCESS)
                .charge("order:" + f.order().id(), BigDecimal.ONE, "USD")).hasMessageContaining("mismatch");
    }

    @Test void legacyOrderWithoutProductIdentityIsRejectedBeforeCharging() {
        Fixture f = fixture(5);
        jdbc.update("UPDATE order_items SET product_id = NULL WHERE order_id = ?", f.order().id());
        assertThatThrownBy(() -> payments.pay(f.owner(), f.order().orderNumber())).hasMessageContaining("product identities");
        verifyNoInteractions(gateway);
        history(f, "CREATED");
    }

    private void scenario(PaymentGateway.Outcome outcome) {
        var provider = new MockPaymentGateway(jdbc, outcome);
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return provider.charge(call.getArgument(0), call.getArgument(1), call.getArgument(2));
        }).when(gateway).charge(anyString(), any(), anyString());
    }

    private Fixture fixture(int stock) {
        UUID owner = UUID.randomUUID(), product = UUID.randomUUID(), cart = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'test')", owner, owner + "@example.com");
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, 'Item', 12.50, 'USD')", product, product.toString());
        ExtractedInventory.jdbc().update("INSERT INTO inventory(product_id, available_quantity, reserved_quantity) VALUES (?, ?, 0)", product, stock);
        jdbc.update("INSERT INTO carts(id, customer_id) VALUES (?, ?)", cart, owner);
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, 2)", UUID.randomUUID(), cart, product);
        authenticate(owner);
        return new Fixture(owner, product, checkout.checkout(owner, UUID.randomUUID().toString()));
    }

    private void stock(Fixture f, int available, int reserved, String status) {
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT available_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(available);
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT reserved_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(reserved);
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT status FROM reservation_orders WHERE order_id = ?", String.class, f.order().id())).isEqualTo(status);
    }
    private void history(Fixture f, String... statuses) {
        assertThat(jdbc.queryForList("SELECT status FROM order_status_history WHERE order_id = ? ORDER BY id", String.class, f.order().id()))
                .containsExactly(statuses);
    }
    private int count(String table, String column, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, id);
    }
    private static void authenticate(UUID owner) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
    }
    private record Fixture(UUID owner, UUID product, OrderResponse order) { }
}
