package com.example.platform;

import com.example.platform.inventory.application.InventoryService;
import com.example.platform.payment.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties = "payment.gateway=local")
class DistributedSagaIntegrationTest {
    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        Phase6IntegrationTest.database(r); ExtractedInventory.configure(r);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired PlatformTransactionManager manager;
    @Autowired ApplicationEventPublisher events;
    @MockitoSpyBean InventoryService inventory;
    @MockitoBean PaymentGateway gateway;
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void inventoryDownBeforeReserveRecoversWithoutLosingTheIntent() {
        var f = fixture();
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(success());
        ExtractedInventory.stop();
        try {
            var pending = payments.pay(f.owner(), f.number());
            assertThat(pending.sagaPhase()).isEqualTo("RESERVING");
            assertThat(pending.recoveryReason()).isNotBlank();
            verifyNoInteractions(gateway);
        } finally { ExtractedInventory.restart(); }
        recoverWithFreshOrchestrator(f);
        assertThat(phase(f)).isEqualTo("DONE");
        stock(f, 3, 0);
    }

    @Test void paymentDeclineWhileInventoryIsDownEventuallyReleasesAfterRestart() {
        var f = fixture();
        when(gateway.charge(anyString(), any(), anyString())).thenAnswer(call -> {
            ExtractedInventory.stop();
            return new PaymentGateway.Result(PaymentGateway.Outcome.DECLINED, null, "declined");
        });
        try {
            var pending = payments.pay(f.owner(), f.number());
            assertThat(pending.sagaPhase()).isEqualTo("RELEASING");
            assertThat(pending.orderStatus()).isEqualTo("PAYMENT_FAILED");
        } finally { ExtractedInventory.restart(); }
        stock(f, 3, 2);
        recoverWithFreshOrchestrator(f);
        assertThat(phase(f)).isEqualTo("DONE");
        assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CANCELLED");
        stock(f, 5, 0);
        verify(gateway, times(1)).charge(anyString(), any(), anyString());
    }

    @Test void paidButInventoryUnavailableRecoversConfirmationWithoutChargingAgain() {
        var f = fixture();
        when(gateway.charge(anyString(), any(), anyString())).thenAnswer(call -> {
            ExtractedInventory.stop(); return success();
        });
        try {
            var pending = payments.pay(f.owner(), f.number());
            assertThat(pending.sagaPhase()).isEqualTo("CONFIRMING");
            assertThat(pending.orderStatus()).isEqualTo("PAID");
        } finally { ExtractedInventory.restart(); }
        recoverWithFreshOrchestrator(f);
        assertThat(phase(f)).isEqualTo("DONE");
        stock(f, 3, 0);
        verify(gateway, times(1)).charge(anyString(), any(), anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, f.order())).isEqualTo(1);
    }

    @Test void lostReservationAcknowledgementIsReplayedWithoutDoubleReserving() {
        var f = fixture();
        when(gateway.charge(anyString(), any(), anyString())).thenReturn(success());
        doAnswer(call -> { call.callRealMethod(); throw new IllegalStateException("lost HTTP acknowledgement"); })
                .when(inventory).reserve(eq(f.order()), anyList());
        assertThat(payments.pay(f.owner(), f.number()).sagaPhase()).isEqualTo("RESERVING");
        stock(f, 3, 2);
        doCallRealMethod().when(inventory).reserve(eq(f.order()), anyList());
        recoverWithFreshOrchestrator(f);
        stock(f, 3, 0);
        assertThat(phase(f)).isEqualTo("DONE");
    }

    @Test void contractRejectsReusedOrderWithChangedItemsAndOppositeTerminalCommand() {
        var f = fixture();
        assertThat(inventory.reserve(f.order(), List.of(new InventoryService.Item(f.product(), 2))).status()).isEqualTo("RESERVED");
        assertThatThrownBy(() -> inventory.reserve(f.order(), List.of(new InventoryService.Item(f.product(), 3))))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
        inventory.move(f.order(), "RELEASED");
        inventory.move(f.order(), "RELEASED");
        assertThatThrownBy(() -> inventory.move(f.order(), "CONFIRMED"))
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
        stock(f, 5, 0);
    }

    @Test void inventoryDatabaseHasNoCoreTablesAndApiRejectsMissingCredentials() {
        fixture();
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT to_regclass('public.orders')", String.class)).isNull();
        assertThatThrownBy(() -> org.springframework.web.client.RestClient.create(ExtractedInventory.url()).put()
                .uri("/internal/v1/stock/" + UUID.randomUUID()).body(Map.of("quantity", 1)).retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
    }

    private void recoverWithFreshOrchestrator(Fixture f) {
        jdbc.update("UPDATE payment_attempts SET saga_next_attempt = CURRENT_TIMESTAMP WHERE order_id = ?", f.order());
        new PaymentRecovery(new PaymentService(jdbc, inventory, gateway, manager, events)).poll();
    }
    private String phase(Fixture f) { return jdbc.queryForObject("SELECT saga_phase FROM payment_attempts WHERE order_id = ?", String.class, f.order()); }
    private PaymentGateway.Result success() { return new PaymentGateway.Result(PaymentGateway.Outcome.SUCCESS, "saga-" + UUID.randomUUID(), null); }
    private void stock(Fixture f, int available, int reserved) {
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT available_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(available);
        assertThat(ExtractedInventory.jdbc().queryForObject("SELECT reserved_quantity FROM inventory WHERE product_id = ?", Integer.class, f.product())).isEqualTo(reserved);
    }
    private Fixture fixture() {
        UUID owner = UUID.randomUUID(), order = UUID.randomUUID(), product = UUID.randomUUID();
        String number = "SAGA-" + order.toString().substring(0, 20);
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES (?,?,'test')", owner, owner + "@test.com");
        jdbc.update("INSERT INTO products(id,sku,name,price,currency) VALUES (?,?,'Item',1,'USD')", product, product.toString());
        jdbc.update("INSERT INTO orders(id,customer_id,order_number,status,total,currency) VALUES (?,?,?,'CREATED',2,'USD')", order, owner, number);
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,sku,name,unit_price,quantity) VALUES (?,?,?,?,'Item',1,2)", UUID.randomUUID(), order, product, product.toString());
        inventory.setStock(product, 5);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
        return new Fixture(owner, order, product, number);
    }
    private record Fixture(UUID owner, UUID order, UUID product, String number) { }
}
