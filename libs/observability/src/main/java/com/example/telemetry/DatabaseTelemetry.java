package com.example.telemetry;

import io.micrometer.core.instrument.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.*;

/** Poll owned tables off the scrape path. Failures invalidate values instead of reporting a false zero backlog. */
public class DatabaseTelemetry {
    private final JdbcTemplate jdbc;
    private final String role;
    private volatile Map<String, Double> snapshot = Map.of();
    private volatile double available;
    private volatile double lastSuccess;
    public DatabaseTelemetry(JdbcTemplate jdbc, MeterRegistry registry, String role) {
        if (!Set.of("core", "inventory", "notification").contains(role)) throw new IllegalArgumentException("Unknown telemetry role");
        this.jdbc = jdbc; this.role = role;
        Gauge.builder("platform.telemetry.database.available", this, t -> t.available).register(registry);
        Gauge.builder("platform.telemetry.database.last.success", this, t -> t.lastSuccess).baseUnit("seconds").register(registry);
        if (!role.equals("inventory")) {
            gauge(registry, "platform.outbox.pending", "outbox", null, null);
            gauge(registry, "platform.outbox.oldest.age", "outboxAge", null, null);
        }
        if (role.equals("core")) {
            gauge(registry, "platform.saga.oldest.age", "sagaAge", null, null);
            for (String phase : List.of("RESERVING", "PAYING", "CONFIRMING", "RELEASING"))
                gauge(registry, "platform.saga.pending", phase, "phase", phase);
            for (String outcome : List.of("PENDING", "UNKNOWN", "SUCCESS", "DECLINED", "ERROR"))
                gauge(registry, "platform.payment.outcomes", outcome, "outcome", outcome);
        } else if (role.equals("inventory")) {
            for (String status : List.of("RESERVED", "REJECTED", "CONFIRMED", "RELEASED"))
                gauge(registry, "platform.reservation.outcomes", status, "outcome", status);
        } else {
            gauge(registry, "platform.dlt.unreplayed", "dlt", null, null);
            gauge(registry, "platform.notification.receipts", "receipts", null, null);
        }
    }
    private void gauge(MeterRegistry registry, String name, String key, String tag, String value) {
        var gauge = Gauge.builder(name, this, t -> t.snapshot.getOrDefault(key, Double.NaN));
        if (tag != null) gauge.tag(tag, value);
        if (name.endsWith(".age")) gauge.baseUnit("seconds");
        gauge.register(registry);
    }
    @Scheduled(fixedDelayString = "${platform.telemetry.poll-ms:10000}", initialDelayString = "${platform.telemetry.initial-delay-ms:1000}")
    public void poll() {
        Map<String, Double> values = new HashMap<>();
        try {
            if (!role.equals("inventory")) {
                values.put("outbox", number("SELECT count(*) FROM outbox_events WHERE status <> 'PUBLISHED'"));
                values.put("outboxAge", number("SELECT COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - min(created_at)),0) FROM outbox_events WHERE status <> 'PUBLISHED'"));
            }
            if (role.equals("core")) {
                values.put("sagaAge", number("SELECT COALESCE(EXTRACT(EPOCH FROM CURRENT_TIMESTAMP - min(created_at)),0) FROM payment_attempts WHERE saga_phase <> 'DONE'"));
                grouped(values, "SELECT saga_phase, count(*) FROM payment_attempts WHERE saga_phase <> 'DONE' GROUP BY saga_phase",
                        List.of("RESERVING", "PAYING", "CONFIRMING", "RELEASING"));
                grouped(values, "SELECT status, count(*) FROM payment_attempts GROUP BY status",
                        List.of("PENDING", "UNKNOWN", "SUCCESS", "DECLINED", "ERROR"));
            } else if (role.equals("inventory")) {
                grouped(values, "SELECT status, count(*) FROM reservation_orders GROUP BY status",
                        List.of("RESERVED", "REJECTED", "CONFIRMED", "RELEASED"));
            } else {
                values.put("dlt", number("SELECT count(*) FROM dead_letters WHERE replay_outbox_id IS NULL"));
                values.put("receipts", number("SELECT count(*) FROM notification_receipts"));
            }
            snapshot = Map.copyOf(values); lastSuccess = System.currentTimeMillis() / 1000.0; available = 1;
        } catch (RuntimeException unavailable) { snapshot = Map.of(); available = 0; }
    }
    private double number(String sql) {
        return jdbc.query(connection -> { var statement = connection.prepareStatement(sql); statement.setQueryTimeout(2); return statement; },
                (rs, row) -> rs.getDouble(1)).getFirst();
    }
    private void grouped(Map<String, Double> values, String sql, List<String> allowed) {
        allowed.forEach(key -> values.put(key, 0.0));
        jdbc.query(connection -> { var statement = connection.prepareStatement(sql); statement.setQueryTimeout(2); return statement; },
                (rs, row) -> { String key = rs.getString(1); if (allowed.contains(key)) values.put(key, rs.getDouble(2)); return key; });
    }
}
