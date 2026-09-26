package com.example.platform.messaging;

// Notification-owned persistence; never loaded by core-platform.

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "platform.messaging.enabled", havingValue = "true")
@org.springframework.context.annotation.Profile("notification")
public class DeadLetterStore {
    private final JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();
    public DeadLetterStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @KafkaListener(id = "notification-dead-letters", topics = "${platform.messaging.order-topic}.DLT",
            groupId = "${platform.messaging.notification-group}.dlt", containerFactory = "dltListenerFactory")
    public void receive(ConsumerRecord<String, String> record) {
        try (var scope = telemetry.open("notification.deadletter", uuid(record.key()), uuid(header(record, "X-Event-ID")), null, null)) {
        int inserted = jdbc.update("INSERT INTO dead_letters(id, dlt_topic, dlt_partition, dlt_offset, message_key, payload, "
                        + "failure_type, failure_message, original_topic, original_partition, original_offset) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (dlt_topic, dlt_partition, dlt_offset) DO NOTHING",
                UUID.randomUUID(), record.topic(), record.partition(), record.offset(), record.key(), record.value(),
                header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN) != null ? header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)
                        : header(record, KafkaHeaders.DLT_EXCEPTION_FQCN), header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC), number(record, KafkaHeaders.DLT_ORIGINAL_PARTITION, 4),
                number(record, KafkaHeaders.DLT_ORIGINAL_OFFSET, 8));
        scope.outcome(inserted == 1 ? "recorded" : "deduplicated");
        }
    }

    private UUID uuid(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value); } catch (IllegalArgumentException invalid) { return null; }
    }

    private String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Number number(ConsumerRecord<?, ?> record, String name, int size) {
        var header = record.headers().lastHeader(name);
        if (header == null || header.value() == null || header.value().length != size) return null;
        var bytes = java.nio.ByteBuffer.wrap(header.value());
        if (size == 4) return bytes.getInt();
        return bytes.getLong();
    }
}
