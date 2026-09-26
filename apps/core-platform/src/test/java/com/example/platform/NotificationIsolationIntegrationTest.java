package com.example.platform;

import com.example.notification.NotificationApplication;
import com.example.platform.messaging.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Duration;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {"platform.messaging.enabled=true", "platform.messaging.relay-enabled=false",
        "payment.recovery.enabled=false", "logging.level.org.apache.kafka=WARN", "logging.level.kafka=WARN"})
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NotificationIsolationIntegrationTest {
    static EmbeddedKafkaKraftBroker broker;
    static PostgreSQLContainer<?> database;
    static final String TOPIC = "isolation-" + UUID.randomUUID();
    static final String GROUP = "notification-isolated-" + UUID.randomUUID();
    ConfigurableApplicationContext notification;
    @Autowired JdbcTemplate core;
    @Autowired ApplicationContext coreContext;
    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager manager;
    @Autowired OutboxRelay relay;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper mapper;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        Phase6IntegrationTest.database(r);
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.brokerListProperty("phase11.broker");
        broker.afterPropertiesSet();
        r.add("spring.kafka.bootstrap-servers", broker::getBrokersAsString);
        r.add("platform.messaging.order-topic", () -> TOPIC);
    }
    @AfterAll void close() {
        if (notification != null) notification.close();
        kafka.getProducerFactory().reset();
        broker.destroy();
        if (database != null) database.stop();
    }
    void startNotification() {
        String url = System.getProperty("phase11.notification-url");
        String user = System.getProperty("phase11.notification-user", "platform");
        String password = System.getProperty("phase11.notification-password", "");
        if (url == null) {
            if (database == null) { database = new PostgreSQLContainer<>("postgres:16-alpine"); database.start(); }
            url = database.getJdbcUrl(); user = database.getUsername(); password = database.getPassword();
        }
        notification = new SpringApplicationBuilder(NotificationApplication.class).profiles("notification").run(
            "--spring.config.name=notification-test", "--server.port=0", "--spring.datasource.url=" + url,
            "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
            "--spring.flyway.locations=classpath:db/notification", "--service.password=test-notification",
            "--platform.messaging.enabled=true", "--platform.messaging.relay-enabled=true",
            "--platform.messaging.order-topic=" + TOPIC, "--platform.messaging.notification-group=" + GROUP,
            "--spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
            "--spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "--spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "--spring.kafka.producer.acks=all",
            "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "--spring.kafka.consumer.enable-auto-commit=false", "--spring.kafka.consumer.auto-offset-reset=earliest",
            "--spring.kafka.listener.ack-mode=record", "--logging.level.org.apache.kafka=WARN", "--logging.level.kafka=WARN");
    }

    @Test void independentConsumerCatchesUpAfterRestartAndDeduplicatesWithItsOwnDatabase() throws Exception {
        assertThat(coreContext.getBeansOfType(OrderNotificationConsumer.class)).isEmpty();
        startNotification();
        JdbcTemplate deliveries = notification.getBean(JdbcTemplate.class);
        assertThat(deliveries.queryForObject("SELECT to_regclass('public.orders')", String.class)).isNull();
        assertThat(notification.getBeansOfType(OrderEventPublisher.class)).isEmpty();
        notification.close();
        var event = OrderConfirmedEvent.create(UUID.randomUUID(), UUID.randomUUID(), "ISOLATED", BigDecimal.TEN, "USD");
        new TransactionTemplate(manager).executeWithoutResult(tx -> publisher.publishEvent(event));
        for (int i = 0; i < 1000 && relay.publishNext(); i++) { }
        kafka.send(TOPIC, event.aggregateId().toString(), mapper.writeValueAsString(event)).get();
        startNotification();
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(count(event)).isEqualTo(1));
        notification.close();
        startNotification();
        kafka.send(TOPIC, event.aggregateId().toString(), mapper.writeValueAsString(event)).get();
        // A later event on the same key proves the duplicate was consumed, rather than merely not yet received.
        var barrier = OrderConfirmedEvent.create(event.aggregateId(), UUID.randomUUID(), "BARRIER", BigDecimal.ONE, "USD");
        kafka.send(TOPIC, barrier.aggregateId().toString(), mapper.writeValueAsString(barrier)).get();
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(count(barrier)).isEqualTo(1));
        assertThat(count(event)).isEqualTo(1);
        assertThat(core.queryForObject("SELECT count(*) FROM notification_receipts WHERE event_id = ?", Integer.class, event.eventId())).isZero();
        assertThat(notification.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, event.eventId())).isEqualTo(1);
        int port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) notification).getWebServer().getPort();
        String endpoint = "http://localhost:" + port;
        assertThatThrownBy(() -> org.springframework.web.client.RestClient.create(endpoint).get()
                .uri("/api/admin/dead-letters").retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
        kafka.send(TOPIC, "bad-key", "malformed-event").get();
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(notification.getBean(JdbcTemplate.class)
                .queryForObject("SELECT count(*) FROM dead_letters WHERE original_topic = ?", Integer.class, TOPIC)).isEqualTo(1));
        UUID dead = notification.getBean(JdbcTemplate.class).queryForObject("SELECT id FROM dead_letters WHERE original_topic = ?", UUID.class, TOPIC);
        var operator = org.springframework.web.client.RestClient.builder().baseUrl(endpoint)
                .defaultHeaders(h -> h.setBasicAuth("operator", "test-notification")).build();
        assertThat(operator.get().uri("/api/admin/dead-letters/" + dead).retrieve().body(String.class)).contains("malformed-event");
        assertThatThrownBy(() -> operator.post().uri("/api/admin/dead-letters/" + dead + "/replay")
                .body(Map.of("reason", "test invalid replay")).retrieve().toBodilessEntity())
                .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Conflict.class);
    }
    int count(OrderConfirmedEvent event) {
        return notification.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM notification_receipts WHERE event_id = ?", Integer.class, event.eventId());
    }
}
