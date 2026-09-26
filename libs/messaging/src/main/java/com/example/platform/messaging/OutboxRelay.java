package com.example.platform.messaging;

// Shared transport contract/infrastructure; each application supplies its own datasource.

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transaction;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.transaction = new TransactionTemplate(manager);
    }

    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.NEVER)
    public boolean publishNext() {
        UUID token = UUID.randomUUID();
        Entry entry = transaction.execute(tx -> {
            var entries = jdbc.query("SELECT id, aggregate_id, topic, payload, attempts, event_id, traceparent, tracestate FROM outbox_events o "
                            + "WHERE ((status = 'PENDING' AND available_at <= CURRENT_TIMESTAMP) "
                            + "OR (status = 'PUBLISHING' AND locked_until <= CURRENT_TIMESTAMP)) "
                            + "AND NOT EXISTS (SELECT 1 FROM outbox_events earlier WHERE earlier.aggregate_id = o.aggregate_id "
                            + "AND earlier.sequence < o.sequence AND earlier.status <> 'PUBLISHED') "
                            + "ORDER BY sequence LIMIT 1 FOR UPDATE OF o SKIP LOCKED",
                    (rs, row) -> new Entry(rs.getObject("id", UUID.class), rs.getObject("aggregate_id", UUID.class),
                            rs.getString("topic"), rs.getString("payload"), rs.getInt("attempts") + 1,
                            rs.getObject("event_id", UUID.class), rs.getString("traceparent"), rs.getString("tracestate")));
            if (entries.isEmpty()) return null;
            Entry selected = entries.getFirst();
            jdbc.update("UPDATE outbox_events SET status = 'PUBLISHING', claim_token = ?, "
                    + "locked_until = CURRENT_TIMESTAMP + INTERVAL '30 seconds', attempts = attempts + 1 WHERE id = ?", token, selected.id());
            return selected;
        });
        if (entry == null) return false;
        try (var scope = telemetry.open("outbox.publish", entry.aggregate(), entry.event(), entry.parent(), entry.state())) {
            // The claim transaction has ended: no database locks span network I/O.
            var record = new org.apache.kafka.clients.producer.ProducerRecord<String, String>(entry.topic(), entry.aggregate().toString(), entry.payload());
            record.headers().add("X-Correlation-ID", entry.aggregate().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            record.headers().add("X-Order-ID", entry.aggregate().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            record.headers().add("X-Event-ID", entry.event().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Kafka observation injects the active publish span; explicit fallback also supports non-observed producers.
            telemetry.headers().forEach((name, value) -> record.headers().add(name, value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            kafka.send(record).get(12, TimeUnit.SECONDS);
            int updated = jdbc.update("UPDATE outbox_events SET status = 'PUBLISHED', published_at = CURRENT_TIMESTAMP, "
                    + "claim_token = NULL, locked_until = NULL, last_error = NULL WHERE id = ? AND claim_token = ?", entry.id(), token);
            scope.outcome(updated == 1 ? "published" : "stale_claim");
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            long delay = Math.min(60000L, 1000L << Math.min(entry.attempts() - 1, 6));
            jdbc.update("UPDATE outbox_events SET status = 'PENDING', claim_token = NULL, locked_until = NULL, "
                            + "available_at = CURRENT_TIMESTAMP + (? * INTERVAL '1 millisecond'), last_error = ? "
                            + "WHERE id = ? AND claim_token = ?", delay, failure.getClass().getSimpleName(), entry.id(), token);
            log.warn("Outbox delivery will retry: id={} attempt={} error={}", entry.id(), entry.attempts(), failure.getClass().getSimpleName());
        }
        return true;
    }

    private record Entry(UUID id, UUID aggregate, String topic, String payload, int attempts, UUID event, String parent, String state) { }
}
