package com.example.platform.payment;

import com.example.platform.inventory.application.InventoryService;
import com.example.platform.order.domain.Order;
import com.example.platform.order.domain.OrderStatus;
import com.example.platform.shared.security.OwnershipGuard;
import com.example.platform.messaging.OrderConfirmedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.UUID;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final PaymentGateway gateway;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();
    @org.springframework.beans.factory.annotation.Autowired
    public void telemetry(com.example.telemetry.FlowTelemetry telemetry) { this.telemetry = telemetry; }
    public PaymentService(JdbcTemplate jdbc, InventoryService inventory, PaymentGateway gateway,
                          PlatformTransactionManager manager, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.inventory = inventory; this.gateway = gateway;
        this.transaction = new TransactionTemplate(manager); this.events = events;
    }

    @Transactional(propagation = Propagation.NEVER)
    public PaymentResponse pay(UUID owner, String number) {
        OwnershipGuard.check(owner);
        UUID order = transaction.execute(tx -> prepare(owner, number));
        advance(order);
        return response(order);
    }

    private UUID prepare(UUID owner, String number) {
        UUID order = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id = ? AND order_number = ? FOR UPDATE",
                UUID.class, owner, number);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM payment_attempts WHERE order_id = ?)", Boolean.class, order))) return order;
        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, order);
        new Order(OrderStatus.valueOf(status)).reserveInventory(); // Validate CREATED, without claiming remote success.
        var items = items(order);
        if (items.isEmpty() || items.stream().anyMatch(i -> i.productId() == null))
            throw new IllegalStateException("order lacks product identities; create a new checkout");
        var context = telemetry.headers();
        jdbc.update("INSERT INTO payment_attempts(id, order_id, idempotency_key, amount, currency, status, saga_phase, traceparent, tracestate) "
                + "SELECT ?, id, ?, total, currency, 'PENDING', 'RESERVING', ?, ? FROM orders WHERE id = ?",
                UUID.randomUUID(), "order:" + order, context.get("traceparent"), context.get("tracestate"), order);
        return order;
    }

    private java.util.List<InventoryService.Item> items(UUID order) {
        return jdbc.query("SELECT product_id, SUM(quantity)::integer FROM order_items WHERE order_id = ? GROUP BY product_id ORDER BY product_id",
                (rs, n) -> new InventoryService.Item(rs.getObject(1, UUID.class), rs.getInt(2)), order);
    }

    /** Every remote command is idempotent; each transition is fenced by the persisted phase under a short row lock. */
    @Transactional(propagation = Propagation.NEVER)
    public void advance(UUID order) {
        var context = jdbc.queryForMap("SELECT p.traceparent, p.tracestate, o.order_number FROM payment_attempts p JOIN orders o ON o.id = p.order_id WHERE p.order_id = ?", order);
        try (var scope = telemetry.open("order.saga", order, null, (String) context.get("traceparent"), (String) context.get("tracestate"))) {
            org.slf4j.MDC.put("order_number", (String) context.get("order_number"));
            advanceSteps(order);
            scope.outcome(intent(order).phase());
        }
    }

    private void advanceSteps(UUID order) {
        try {
            for (int step = 0; step < 3; step++) {
                Intent intent = intent(order);
                switch (intent.phase()) {
                    case "DONE" -> { return; }
                    case "RESERVING" -> {
                        String result = inventory.reserve(order, items(order)).status();
                        if (!java.util.Set.of("RESERVED", "REJECTED").contains(result))
                            throw new IllegalStateException("Unexpected reservation status: " + result);
                        transition(order, "RESERVING", () -> {
                            if ("REJECTED".equals(result)) {
                                saveStatus(order, OrderStatus.CANCELLED);
                                jdbc.update("UPDATE payment_attempts SET status = 'ERROR', failure_reason = 'Insufficient inventory', saga_phase = 'DONE' WHERE order_id = ?", order);
                            } else {
                                saveStatus(order, OrderStatus.RESERVED);
                                jdbc.update("UPDATE payment_attempts SET saga_phase = 'PAYING' WHERE order_id = ?", order);
                            }
                        });
                    }
                    case "PAYING" -> {
                        PaymentGateway.Result result;
                        try { result = gateway.charge(intent.key(), intent.amount(), intent.currency()); }
                        catch (RuntimeException uncertain) { result = new PaymentGateway.Result(PaymentGateway.Outcome.TIMEOUT, null, "Provider outcome unknown"); }
                        PaymentGateway.Result observed = result;
                        transition(order, "PAYING", () -> {
                            boolean unknown = observed.outcome() == PaymentGateway.Outcome.TIMEOUT;
                            jdbc.update("UPDATE payment_attempts SET status = ?, failure_reason = ?, gateway_reference = ?, saga_phase = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?",
                                    unknown ? "UNKNOWN" : observed.outcome().name(), observed.reason(), observed.reference(),
                                    unknown ? "PAYING" : observed.outcome() == PaymentGateway.Outcome.SUCCESS ? "CONFIRMING" : "RELEASING", order);
                            if (!unknown) saveStatus(order, observed.outcome() == PaymentGateway.Outcome.SUCCESS ? OrderStatus.PAID : OrderStatus.PAYMENT_FAILED);
                        });
                        if (observed.outcome() == PaymentGateway.Outcome.TIMEOUT) {
                            defer(order, "Provider outcome unknown; same-key reconciliation pending");
                            return;
                        }
                    }
                    case "CONFIRMING", "RELEASING" -> {
                        boolean paid = "CONFIRMING".equals(intent.phase());
                        String target = paid ? "CONFIRMED" : "RELEASED";
                        if (!target.equals(inventory.move(order, target).status())) throw new IllegalStateException("Inventory outcome conflicts with saga");
                        transition(order, intent.phase(), () -> {
                            saveStatus(order, paid ? OrderStatus.CONFIRMED : OrderStatus.CANCELLED);
                            jdbc.update("UPDATE payment_attempts SET saga_phase = 'DONE', saga_error = NULL WHERE order_id = ?", order);
                            if (paid) {
                                var event = jdbc.queryForObject("SELECT customer_id, order_number, total, currency FROM orders WHERE id = ?",
                                        (rs, n) -> OrderConfirmedEvent.create(order, rs.getObject(1, UUID.class), rs.getString(2), rs.getBigDecimal(3), rs.getString(4)), order);
                                events.publishEvent(event);
                            }
                        });
                    }
                    default -> throw new IllegalStateException("Unknown saga phase");
                }
            }
        } catch (RuntimeException failure) {
            defer(order, failure.getClass().getSimpleName());
        }
    }

    private void transition(UUID order, String expected, Runnable change) {
        transaction.executeWithoutResult(tx -> {
            String phase = jdbc.queryForObject("SELECT saga_phase FROM payment_attempts WHERE order_id = ? FOR UPDATE", String.class, order);
            if (!expected.equals(phase)) return; // A concurrent request or recovery worker already progressed.
            change.run();
            jdbc.update("UPDATE payment_attempts SET saga_error = NULL, saga_attempts = 0, saga_next_attempt = CURRENT_TIMESTAMP WHERE order_id = ?", order);
        });
    }

    private void defer(UUID order, String error) {
        org.slf4j.LoggerFactory.getLogger(PaymentService.class).atWarn().addKeyValue("outcome", "deferred")
                .addKeyValue("reason", error).log("Saga recovery scheduled");
        jdbc.update("UPDATE payment_attempts SET saga_attempts = saga_attempts + 1, saga_error = ?, "
                + "saga_next_attempt = CURRENT_TIMESTAMP + (LEAST(60, POWER(2, LEAST(saga_attempts, 6))) * INTERVAL '1 second') "
                + "WHERE order_id = ? AND saga_phase <> 'DONE'", error, order);
    }

    public void recover() {
        var due = jdbc.query("SELECT order_id FROM payment_attempts WHERE saga_phase <> 'DONE' AND saga_next_attempt <= CURRENT_TIMESTAMP ORDER BY saga_next_attempt LIMIT 20",
                (rs, n) -> rs.getObject(1, UUID.class));
        for (UUID order : due) advance(order);
    }
    private Intent intent(UUID order) {
        return jdbc.queryForObject("SELECT idempotency_key, amount, currency, saga_phase FROM payment_attempts WHERE order_id = ?",
                (rs, n) -> new Intent(rs.getString(1), rs.getBigDecimal(2), rs.getString(3), rs.getString(4)), order);
    }
    private void saveStatus(UUID order, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ? WHERE id = ?", status.name(), order);
        jdbc.update("INSERT INTO order_status_history(order_id, status) VALUES (?, ?)", order, status.name());
    }
    private PaymentResponse response(UUID order) {
        return jdbc.queryForObject("SELECT p.id, o.order_number, o.status AS order_status, p.status, p.failure_reason, p.gateway_reference, p.saga_phase, p.saga_error "
                + "FROM payment_attempts p JOIN orders o ON o.id = p.order_id WHERE o.id = ?",
                (rs, n) -> new PaymentResponse(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)), order);
    }
    private record Intent(String key, BigDecimal amount, String currency, String phase) { }
    public record PaymentResponse(UUID paymentId, String orderNumber, String orderStatus, String paymentStatus,
                                  String failureReason, String gatewayReference, String sagaPhase, String recoveryReason) { }
}
