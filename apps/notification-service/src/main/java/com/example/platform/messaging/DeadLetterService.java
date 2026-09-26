package com.example.platform.messaging;

// Notification-owned persistence; never loaded by core-platform.

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@org.springframework.context.annotation.Profile("notification")
public class DeadLetterService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String topic;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();

    public DeadLetterService(JdbcTemplate jdbc, ObjectMapper mapper, @Value("${platform.messaging.order-topic}") String topic) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.topic = topic;
    }

    public List<Map<String, Object>> list() {
        return jdbc.queryForList("SELECT id, original_topic, failure_type, recorded_at, replay_outbox_id "
                + "FROM dead_letters ORDER BY recorded_at DESC, id LIMIT 50");
    }

    public Map<String, Object> get(UUID id) {
        return jdbc.queryForMap("SELECT * FROM dead_letters WHERE id = ?", id);
    }

    @Transactional
    public UUID replay(UUID id, String actor, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("Replay reason is required (max 500 characters)");
        var row = jdbc.queryForMap("SELECT * FROM dead_letters WHERE id = ? FOR UPDATE", id);
        UUID existing = (UUID) row.get("replay_outbox_id");
        if (existing != null) return existing;
        if (!topic.equals(row.get("original_topic"))) throw new IllegalArgumentException("Unsupported original topic");
        OrderConfirmedEvent event;
        try {
            if (row.get("payload") == null) throw new IllegalArgumentException("Cannot replay an empty payload");
            event = mapper.readValue((String) row.get("payload"), OrderConfirmedEvent.class);
            if (event == null) throw new IllegalArgumentException("Cannot replay a null event");
            event.validate((String) row.get("message_key"));
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Malformed event must be investigated, not blindly replayed");
        }
        UUID queued = UUID.randomUUID();
        var context = telemetry.headers();
        // Preserve the original event ID and bytes: replay cannot evade consumer deduplication.
        jdbc.update("INSERT INTO outbox_events(id, event_id, aggregate_type, aggregate_id, event_type, version, topic, payload, traceparent, tracestate) "
                        + "VALUES (?, ?, 'Order', ?, ?, ?, ?, ?, ?, ?)", queued, event.eventId(), event.aggregateId(),
                event.eventType(), event.version(), topic, row.get("payload"), context.get("traceparent"), context.get("tracestate"));
        jdbc.update("UPDATE dead_letters SET replay_outbox_id = ?, replay_requested_at = CURRENT_TIMESTAMP, "
                + "replay_requested_by = ?, replay_reason = ? WHERE id = ?", queued, actor, reason, id);
        return queued;
    }
}
