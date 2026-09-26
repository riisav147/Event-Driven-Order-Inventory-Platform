package com.example.platform.messaging;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderMessagingTest {
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final OrderConfirmedEvent event = OrderConfirmedEvent.create(UUID.randomUUID(), UUID.randomUUID(), "ORD-TEST", BigDecimal.TEN, "USD");

    @Test void stableJsonEnvelopeToleratesAdditiveFields() throws Exception {
        String json = mapper.writeValueAsString(event);
        assertThat(json).contains("\"event_id\"", "\"event_type\"", "\"occurred_at\"", "\"correlation_id\"", "\"aggregate_id\"", "\"customer_id\"");
        String expanded = json.substring(0, json.length() - 1) + ",\"future_field\":true}";
        assertThat(mapper.readValue(expanded, OrderConfirmedEvent.class)).isEqualTo(event);
    }

    @Test void invalidVersionKeyOrPayloadNeverProducesNotification() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var consumer = new OrderNotificationConsumer(jdbc, mapper);
        String json = mapper.writeValueAsString(event);
        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>("orders", 0, 0, "wrong", json)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>("orders", 0, 0, event.aggregateId().toString(), json.replace("\"version\":1", "\"version\":2"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> consumer.receive(new ConsumerRecord<>("orders", 0, 0, event.aggregateId().toString(), "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void outboxPersistenceFailurePropagatesToBusinessTransaction() {
        var jdbc = mock(JdbcTemplate.class);
        var publisher = new OrderEventPublisher(jdbc, mapper, "orders");
        when(jdbc.update(anyString(), any(Object[].class))).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void failedDeadLetterSendIsNotReportedAsSuccessfulRecovery() {
        org.springframework.kafka.core.KafkaTemplate<String, String> kafka = mock(org.springframework.kafka.core.KafkaTemplate.class);
        org.springframework.kafka.core.ProducerFactory<String, String> factory = mock(org.springframework.kafka.core.ProducerFactory.class);
        when(kafka.getProducerFactory()).thenReturn(factory);
        when(factory.getConfigurationProperties()).thenReturn(java.util.Map.of("delivery.timeout.ms", 1000));
        when(kafka.send(any(org.apache.kafka.clients.producer.ProducerRecord.class)))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("DLT unavailable")));
        var handler = new KafkaConfiguration().notificationErrorHandler(kafka, "orders");
        boolean recovered = handler.handleOne(new IllegalArgumentException("poison record"),
                new ConsumerRecord<>("orders", 0, 10L, "key", "invalid"),
                mock(org.apache.kafka.clients.consumer.Consumer.class), mock(org.springframework.kafka.listener.MessageListenerContainer.class));
        assertThat(recovered).isFalse();
        verify(kafka).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
    }
}
