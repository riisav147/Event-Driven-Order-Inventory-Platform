package com.example.platform.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OrderEventPublisher {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String topic;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();

    public OrderEventPublisher(JdbcTemplate jdbc, ObjectMapper mapper,
                               @Value("${platform.messaging.order-topic}") String topic) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.topic = topic;
    }

    // Capture intent synchronously in the business transaction, even with Kafka disabled.
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(OrderConfirmedEvent event) {
        event.validate(event.aggregateId().toString());
        try {
            var context = telemetry.headers();
            jdbc.update("INSERT INTO outbox_events(id, event_id, aggregate_type, aggregate_id, event_type, version, topic, payload, traceparent, tracestate) "
                            + "VALUES (?, ?, 'Order', ?, ?, ?, ?, ?, ?, ?)", event.eventId(), event.eventId(), event.aggregateId(),
                    event.eventType(), event.version(), topic, mapper.writeValueAsString(event), context.get("traceparent"), context.get("tracestate"));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize order event", failure);
        }
    }
}
