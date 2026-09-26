package com.example.platform.messaging;

// Shared transport contract/infrastructure; each application supplies its own datasource.

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record OrderConfirmedEvent(UUID eventId, String eventType, int version, Instant occurredAt,
                                  UUID correlationId, UUID aggregateId, Payload payload) {
    public static OrderConfirmedEvent create(UUID orderId, UUID customerId, String orderNumber,
                                              BigDecimal total, String currency) {
        return new OrderConfirmedEvent(UUID.randomUUID(), "OrderConfirmed", 1, Instant.now(), orderId,
                orderId, new Payload(customerId, orderNumber, total, currency));
    }

    public void validate(String key) {
        if (eventId == null || !"OrderConfirmed".equals(eventType) || version != 1 || occurredAt == null
                || correlationId == null || aggregateId == null || !aggregateId.toString().equals(key)
                || payload == null || payload.customerId() == null || payload.orderNumber() == null
                || payload.orderNumber().isBlank() || payload.orderNumber().length() > 32
                || payload.total() == null || payload.total().signum() < 0
                || payload.currency() == null || !payload.currency().matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Invalid or unsupported OrderConfirmed event");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Payload(UUID customerId, String orderNumber, BigDecimal total, String currency) { }
}
