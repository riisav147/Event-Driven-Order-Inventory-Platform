package com.example.platform;

import com.example.platform.messaging.OrderConfirmedEvent;
import com.example.platform.messaging.OrderEventPublisher;
import com.example.platform.messaging.OrderNotificationConsumer;
import com.example.platform.messaging.OutboxRelay;
import com.example.platform.messaging.DeadLetterService;
import com.example.platform.shared.security.JwtService;
import com.example.platform.order.application.CheckoutService;
import com.example.platform.payment.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@org.springframework.test.context.ActiveProfiles("notification")
@SpringBootTest(properties = {"payment.gateway=local", "platform.messaging.enabled=true", "logging.level.org.apache.kafka=WARN",
        "platform.messaging.relay-enabled=false",
        "logging.level.kafka=WARN", "logging.level.state.change.logger=WARN", "logging.level.org.apache.zookeeper=WARN"})
@AutoConfigureMockMvc
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KafkaIntegrationTest {
    private static EmbeddedKafkaKraftBroker embedded;
    private static KafkaContainer container;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry registry) {
        Phase6IntegrationTest.database(registry);
        ExtractedInventory.configure(registry);
        String brokers;
        if (Boolean.getBoolean("phase8.testcontainers")) {
            container = new KafkaContainer("apache/kafka:3.9.1");
            container.start();
            brokers = container.getBootstrapServers();
        } else {
            embedded = new EmbeddedKafkaKraftBroker(1, 3);
            embedded.brokerListProperty("phase8.embedded.brokers");
            embedded.afterPropertiesSet();
            brokers = embedded.getBrokersAsString();
        }
        String address = brokers;
        registry.add("spring.kafka.bootstrap-servers", () -> address);
        registry.add("platform.messaging.order-topic", () -> "platform.orders.test." + GROUP);
        registry.add("platform.messaging.notification-group", () -> "notifications-test-" + GROUP);
    }

    private static final UUID GROUP = UUID.randomUUID();
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired PaymentService payments;
    @Autowired CheckoutService checkout;
    @Autowired ApplicationEventPublisher events;
    @Autowired PlatformTransactionManager manager;
    @Autowired KafkaListenerEndpointRegistry listeners;
    @Autowired ObjectMapper mapper;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired OutboxRelay relay;
    @Autowired DeadLetterService deadLetters;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoSpyBean OrderEventPublisher publisher;
    @MockitoSpyBean OrderNotificationConsumer notificationConsumer;
    @Value("${platform.messaging.order-topic}") String topic;
    @Value("${spring.kafka.bootstrap-servers}") String brokers;
    @Value("${platform.messaging.notification-group}") String group;

    @BeforeEach void drainPreviousIntents() { drain(); }

    @AfterEach void clearPrincipal() { SecurityContextHolder.clearContext(); }
    @AfterAll void shutdown() {
        listeners.stop();
        kafka.getProducerFactory().reset();
        if (embedded != null) embedded.destroy();
        if (container != null) container.stop();
    }

    @Test void confirmedPaymentIsConsumedAsynchronouslyAndRetryDoesNotRepublish() throws Exception {
        stopListener();
        Fixture f = fixture();
        try {
            assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CONFIRMED");
            assertThat(count(f.id())).isZero();
            assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CONFIRMED");
            verify(publisher, times(1)).publish(argThat(event -> event.aggregateId().equals(f.id())));
            assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE aggregate_id = ?", String.class, f.id())).isEqualTo("PENDING");
            // Simulates resuming a process that died after business commit but before publication.
            new OutboxRelay(jdbc, kafka, manager).publishNext();
        } finally { listeners.getListenerContainer("order-notifications").start(); }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(f.id())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT customer_id FROM notification_receipts WHERE order_id = ?", UUID.class, f.id())).isEqualTo(f.owner());
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM notification_receipts WHERE order_id = ?", UUID.class, f.id())).isEqualTo(f.id());
    }

    @Test void rollbackDoesNotPublishAnIntegrationEvent() {
        var event = event(UUID.randomUUID(), "ORD-ROLLBACK");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            events.publishEvent(event);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE event_id = ?", Integer.class, event.eventId())).isZero();
        assertThat(count(event.aggregateId())).isZero();
    }

    @Test void failedPaymentDoesNotPublishConfirmation() {
        Fixture f = fixture();
        // Durable provider decision forces the real mock adapter to return DECLINED.
        jdbc.update("INSERT INTO mock_gateway_charges(idempotency_key, amount, currency, outcome, reference) "
                + "VALUES (?, 25, 'USD', 'DECLINED', ?)", "order:" + f.id(), "declined-" + UUID.randomUUID());
        assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CANCELLED");
        verify(publisher, never()).publish(argThat(event -> event.aggregateId().equals(f.id())));
        assertThat(count(f.id())).isZero();
    }

    @Test void sameKeyPreservesProducerSequenceWithinOnePartition() throws Exception {
        UUID order = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            var event = event(order, "ORD-SEQUENCE-" + i);
            ids.add(event.eventId());
            kafka.send(topic, order.toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        }
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(order)).isEqualTo(6));
        assertThat(jdbc.queryForList("SELECT event_id FROM notification_receipts WHERE order_id = ? ORDER BY kafka_offset", UUID.class, order))
                .containsExactlyElementsOf(ids);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT kafka_partition) FROM notification_receipts WHERE order_id = ?", Integer.class, order)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT event_id FROM notification_receipts WHERE order_id = ? ORDER BY recorded_at, kafka_offset", UUID.class, order))
                .containsExactlyElementsOf(ids);
    }

    @Test void restartAndExplicitReplayPreserveOneNotificationPerEvent() throws Exception {
        UUID order = UUID.randomUUID();
        var event = event(order, "ORD-REPLAY");
        var metadata = kafka.send(topic, order.toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        var partition = new TopicPartition(topic, metadata.partition());
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", brokers))) {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(count(order)).isEqualTo(1);
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
                assertThat(offset).isNotNull();
                assertThat(offset.offset()).isGreaterThan(metadata.offset());
            });
            stopListener();
            var next = event(order, "ORD-AFTER-RESTART");
            kafka.send(topic, order.toString(), mapper.writeValueAsString(next)).get(10, TimeUnit.SECONDS);
            listeners.getListenerContainer("order-notifications").start();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(order)).isEqualTo(2));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_receipts WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
            stopListener();
            try {
                admin.alterConsumerGroupOffsets(group, Map.of(partition, new OffsetAndMetadata(metadata.offset())))
                        .all().get(10, TimeUnit.SECONDS);
            } finally { listeners.getListenerContainer("order-notifications").start(); }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
                assertThat(offset.offset()).isGreaterThan(metadata.offset() + 1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM notification_receipts WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
                assertThat(count(order)).isEqualTo(2);
            });
        }
    }

    @Test void consumerDatabaseFailureDoesNotAdvanceOffsetAndRetries() throws Exception {
        UUID order = UUID.randomUUID();
        var failing = new java.util.concurrent.atomic.AtomicBoolean(true);
        var attempted = new CountDownLatch(1);
        doAnswer(call -> {
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = call.getArgument(0);
            if (order.toString().equals(record.key()) && failing.get()) {
                attempted.countDown();
                throw new org.springframework.dao.DataAccessResourceFailureException("simulated database outage");
            }
            return call.callRealMethod();
        }).when(notificationConsumer).receive(any());
        var event = event(order, "ORD-DB-RETRY");
        var metadata = kafka.send(topic, order.toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", brokers))) {
            try {
                assertThat(attempted.await(15, TimeUnit.SECONDS)).isTrue();
                assertThat(count(order)).isZero();
                var offset = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS)
                        .get(new TopicPartition(topic, metadata.partition()));
                if (offset != null) assertThat(offset.offset()).isLessThanOrEqualTo(metadata.offset());
            } finally { failing.set(false); }
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(order)).isEqualTo(1));
        }
    }

    private void stopListener() throws InterruptedException {
        var stopped = new CountDownLatch(1);
        listeners.getListenerContainer("order-notifications").stop(stopped::countDown);
        assertThat(stopped.await(15, TimeUnit.SECONDS)).isTrue();
    }

    @Test void outboxFailureRollsBackPaymentCompletionAndCanRecover() {
        Fixture f = fixture();
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("simulated failure before business commit");
        }).when(jdbc).update(startsWith("INSERT INTO outbox_events"), any(Object[].class));
        assertThat(payments.pay(f.owner(), f.number()).sagaPhase()).isEqualTo("CONFIRMING");
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, f.id())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, f.id())).isZero();
        doCallRealMethod().when(jdbc).update(startsWith("INSERT INTO outbox_events"), any(Object[].class));
        assertThat(payments.pay(f.owner(), f.number()).orderStatus()).isEqualTo("CONFIRMED");
        drain();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(f.id())).isEqualTo(1));
    }

    @Test @SuppressWarnings("unchecked")
    void failedSendStaysDurableAndRetriesAfterBackoff() {
        var event = queue(UUID.randomUUID(), "ORD-OUTAGE");
        KafkaTemplate<String, String> offline = mock(KafkaTemplate.class);
        when(offline.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenReturn(
                java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("broker unavailable")));
        assertThat(new OutboxRelay(jdbc, offline, manager).publishNext()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, event.eventId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE id = ?", Integer.class, event.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available_at > CURRENT_TIMESTAMP FROM outbox_events WHERE id = ?", Boolean.class, event.eventId())).isTrue();
        assertThat(new OutboxRelay(jdbc, offline, manager).publishNext()).isFalse();
        jdbc.update("UPDATE outbox_events SET available_at = CURRENT_TIMESTAMP WHERE id = ?", event.eventId());
        drain();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.aggregateId())).isEqualTo(1));
    }

    @Test void ackBeforePublisherCrashIsReplayedAfterLeaseExpiryWithoutDuplicateEffect() throws Exception {
        var event = queue(UUID.randomUUID(), "ORD-CRASH");
        kafka.send(topic, event.aggregateId().toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.aggregateId())).isEqualTo(1));
        jdbc.update("UPDATE outbox_events SET status = 'PUBLISHING', claim_token = ?, locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?",
                UUID.randomUUID(), event.eventId());
        new OutboxRelay(jdbc, kafka, manager).publishNext();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, event.eventId())).isEqualTo("PUBLISHED");
        await().during(Duration.ofMillis(500)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(count(event.aggregateId())).isEqualTo(1));
    }

    @Test void concurrentRelaysClaimRowsOnceAndMaintainAggregateSequence() throws Exception {
        UUID order = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) ids.add(queue(order, "ORD-CONCURRENT-" + i).eventId());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(6)) {
            var work = java.util.stream.IntStream.range(0, 12).mapToObj(i -> (java.util.concurrent.Callable<Boolean>)
                    () -> new OutboxRelay(jdbc, kafka, manager).publishNext()).toList();
            for (var future : executor.invokeAll(work, 30, TimeUnit.SECONDS)) future.get();
        }
        drain();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(order)).isEqualTo(8));
        assertThat(jdbc.queryForList("SELECT attempts FROM outbox_events WHERE aggregate_id = ?", Integer.class, order)).containsOnly(1);
        assertThat(jdbc.queryForList("SELECT event_id FROM notification_receipts WHERE order_id = ? ORDER BY kafka_offset", UUID.class, order)).containsExactlyElementsOf(ids);
    }

    @Test void consumerMarkerRollsBackWithFailedSideEffect() throws Exception {
        var event = event(UUID.randomUUID(), "ORD-ATOMIC");
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 123L, event.aggregateId().toString(), mapper.writeValueAsString(event));
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("receipt insert failed"))
                .when(jdbc).update(startsWith("INSERT INTO notification_receipts"), any(Object[].class));
        try {
            assertThatThrownBy(() -> notificationConsumer.receive(record)).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, event.eventId())).isZero();
        } finally {
            doCallRealMethod().when(jdbc).update(startsWith("INSERT INTO notification_receipts"), any(Object[].class));
        }
        notificationConsumer.receive(record);
        notificationConsumer.receive(record);
        assertThat(count(event.aggregateId())).isEqualTo(1);
    }

    @Test void concurrentDuplicateConsumersCommitOneProtectedEffect() throws Exception {
        var event = event(UUID.randomUUID(), "ORD-DUPLICATE");
        var record = new org.apache.kafka.clients.consumer.ConsumerRecord<>(topic, 0, 456L, event.aggregateId().toString(), mapper.writeValueAsString(event));
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
            var work = java.util.stream.IntStream.range(0, 24).mapToObj(i -> (java.util.concurrent.Callable<Void>) () -> {
                notificationConsumer.receive(record);
                return null;
            }).toList();
            for (var result : executor.invokeAll(work, 20, TimeUnit.SECONDS)) result.get();
        }
        assertThat(count(event.aggregateId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
    }

    @Test @SuppressWarnings("unchecked")
    void staleRelayCannotMarkAnotherWorkersClaimPublished() {
        var event = queue(UUID.randomUUID(), "ORD-FENCED");
        UUID successor = UUID.randomUUID();
        KafkaTemplate<String, String> delayed = mock(KafkaTemplate.class);
        when(delayed.send(any(org.apache.kafka.clients.producer.ProducerRecord.class))).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.update("UPDATE outbox_events SET claim_token = ? WHERE id = ?", successor, event.eventId());
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
        new OutboxRelay(jdbc, delayed, manager).publishNext();
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, event.eventId())).isEqualTo("PUBLISHING");
        assertThat(jdbc.queryForObject("SELECT claim_token FROM outbox_events WHERE id = ?", UUID.class, event.eventId())).isEqualTo(successor);
        jdbc.update("UPDATE outbox_events SET locked_until = CURRENT_TIMESTAMP - INTERVAL '1 second' WHERE id = ?", event.eventId());
        drain();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.aggregateId())).isEqualTo(1));
    }

    @Test void permanentFailureGoesToDltAndAdminReplayIsAuditedAndIdempotent() throws Exception {
        var event = event(UUID.randomUUID(), "ORD-DLT");
        doAnswer(call -> {
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = call.getArgument(0);
            if (event.aggregateId().toString().equals(record.key())) throw new IllegalArgumentException("simulated permanent consumer bug");
            return call.callRealMethod();
        }).when(notificationConsumer).receive(any());
        kafka.send(topic, event.aggregateId().toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deadCount(event.aggregateId())).isEqualTo(1));
        verify(notificationConsumer, times(1)).receive(argThat(record -> event.aggregateId().toString().equals(record.key())));
        assertThat(count(event.aggregateId())).isZero();
        UUID deadId = jdbc.queryForObject("SELECT id FROM dead_letters WHERE message_key = ?", UUID.class, event.aggregateId().toString());
        doCallRealMethod().when(notificationConsumer).receive(any());
        String path = "/api/admin/dead-letters/" + deadId + "/replay";
        String customer = jwt.issue(UUID.randomUUID(), "CUSTOMER"), admin = jwt.issue(UUID.randomUUID(), "ADMIN");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + customer).contentType("application/json").content("{\"reason\":\"fixed consumer\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        String first = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content("{\"reason\":\"fixed consumer\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()).andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
                .header("Authorization", "Bearer " + admin).contentType("application/json").content("{\"reason\":\"retry request\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(retry).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT replay_reason FROM dead_letters WHERE id = ?", String.class, deadId)).isEqualTo("fixed consumer");
        drain();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(count(event.aggregateId())).isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT event_id FROM notification_receipts WHERE order_id = ?", UUID.class, event.aggregateId())).isEqualTo(event.eventId());
    }

    @Test void exhaustedTransientFailureUsesBoundedRetriesThenDlt() throws Exception {
        var event = event(UUID.randomUUID(), "ORD-RETRY-LIMIT");
        doAnswer(call -> {
            org.apache.kafka.clients.consumer.ConsumerRecord<String, String> record = call.getArgument(0);
            if (event.aggregateId().toString().equals(record.key())) throw new org.springframework.dao.DataAccessResourceFailureException("persistent outage");
            return call.callRealMethod();
        }).when(notificationConsumer).receive(any());
        kafka.send(topic, event.aggregateId().toString(), mapper.writeValueAsString(event)).get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(deadCount(event.aggregateId())).isEqualTo(1));
        verify(notificationConsumer, times(4)).receive(argThat(record -> event.aggregateId().toString().equals(record.key())));
        assertThat(count(event.aggregateId())).isZero();
    }

    @Test void malformedJsonIsRecordedAndCannotBeBlindlyReplayed() throws Exception {
        UUID key = UUID.randomUUID();
        kafka.send(topic, key.toString(), "not-json").get(10, TimeUnit.SECONDS);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> assertThat(deadCount(key)).isEqualTo(1));
        UUID deadId = jdbc.queryForObject("SELECT id FROM dead_letters WHERE message_key = ?", UUID.class, key.toString());
        assertThat(deadLetters.get(deadId).get("failure_type")).isNotNull();
        assertThatThrownBy(() -> deadLetters.replay(deadId, "operator", "investigated")).hasMessageContaining("Malformed");
        assertThat(deadLetters.get(deadId).get("replay_outbox_id")).isNull();
    }

    private int deadCount(UUID key) { return jdbc.queryForObject("SELECT count(*) FROM dead_letters WHERE message_key = ?", Integer.class, key.toString()); }
    private OrderConfirmedEvent queue(UUID order, String number) {
        var event = event(order, number);
        new TransactionTemplate(manager).executeWithoutResult(tx -> events.publishEvent(event));
        return event;
    }
    private void drain() {
        for (int i = 0; i < 500; i++) if (!relay.publishNext()) return;
        throw new AssertionError("Outbox did not drain");
    }
    private OrderConfirmedEvent event(UUID order, String number) {
        return OrderConfirmedEvent.create(order, UUID.randomUUID(), number, new BigDecimal("25.00"), "USD");
    }
    private int count(UUID order) {
        return jdbc.queryForObject("SELECT count(*) FROM notification_receipts WHERE order_id = ?", Integer.class, order);
    }
    private Fixture fixture() {
        UUID owner = UUID.randomUUID(), product = UUID.randomUUID(), cart = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id, email, password_hash) VALUES (?, ?, 'test')", owner, owner + "@example.com");
        jdbc.update("INSERT INTO products(id, sku, name, price, currency) VALUES (?, ?, 'Item', 12.50, 'USD')", product, product.toString());
        ExtractedInventory.jdbc().update("INSERT INTO inventory(product_id, available_quantity, reserved_quantity) VALUES (?, 5, 0)", product);
        jdbc.update("INSERT INTO carts(id, customer_id) VALUES (?, ?)", cart, owner);
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, 2)", UUID.randomUUID(), cart, product);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(owner.toString(), null, List.of()));
        var order = checkout.checkout(owner, UUID.randomUUID().toString());
        return new Fixture(owner, order.id(), order.orderNumber());
    }
    private record Fixture(UUID owner, UUID id, String number) { }
}
