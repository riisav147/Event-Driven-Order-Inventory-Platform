package com.example.platform.messaging;

// Notification-owned persistence; never loaded by core-platform.

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
@org.springframework.context.annotation.Profile("notification")
public class OrderNotificationConsumer {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();

    public OrderNotificationConsumer(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @KafkaListener(id = "order-notifications", topics = "${platform.messaging.order-topic}",
            groupId = "${platform.messaging.notification-group}")
    @Transactional
    public void receive(ConsumerRecord<String, String> record) throws JsonProcessingException {
        if (record.value() == null) throw new IllegalArgumentException("Null event payload");
        OrderConfirmedEvent event = mapper.readValue(record.value(), OrderConfirmedEvent.class);
        if (event == null) throw new IllegalArgumentException("Null event envelope");
        event.validate(record.key());
        try (var scope = telemetry.open("notification.receive", event.aggregateId(), event.eventId(), null, null)) {
        org.slf4j.MDC.put("order_number", event.payload().orderNumber());
        int claimed = jdbc.update("INSERT INTO processed_events(consumer_id, event_id) VALUES ('order-notifications', ?) "
                + "ON CONFLICT DO NOTHING", event.eventId());
        if (claimed == 0) { scope.outcome("deduplicated"); return; }
        // Simulated notification only. Deliberately no email/network side effect in this phase.
        jdbc.update("INSERT INTO notification_receipts(id, event_id, event_type, event_version, occurred_at, "
                        + "correlation_id, order_id, customer_id, order_number, kafka_topic, kafka_partition, kafka_offset) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), event.eventId(), event.eventType(), event.version(), Timestamp.from(event.occurredAt()),
                event.correlationId(), event.aggregateId(), event.payload().customerId(), event.payload().orderNumber(),
                record.topic(), record.partition(), record.offset());
        scope.outcome("receipt_staged");
        }
    }
}
