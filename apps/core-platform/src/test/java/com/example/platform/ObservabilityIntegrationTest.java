package com.example.platform;

import com.example.notification.NotificationApplication;
import com.example.telemetry.DatabaseTelemetry;
import com.example.platform.messaging.*;
import com.example.platform.shared.security.JwtService;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.*;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "payment.gateway=local", "platform.messaging.enabled=true", "platform.messaging.relay-enabled=false",
    "platform.telemetry.database-enabled=true", "platform.telemetry.initial-delay-ms=3600000",
    "management.tracing.sampling.probability=1", "logging.level.org.apache.kafka=WARN", "logging.level.kafka=WARN"})
@AutoConfigureObservability
@Import(TraceCapture.class)
@DirtiesContext
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ObservabilityIntegrationTest {
    static EmbeddedKafkaKraftBroker broker;
    static PostgreSQLContainer<?> notificationDb;
    static final String TOPIC = "observability-" + UUID.randomUUID();
    static final String GROUP = "observability-" + UUID.randomUUID();
    ConfigurableApplicationContext notification;
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired OutboxRelay relay;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired MeterRegistry metrics;
    @Autowired DatabaseTelemetry databaseMetrics;
    @Autowired ConfigurableApplicationContext application;

    @DynamicPropertySource static void infrastructure(DynamicPropertyRegistry r) {
        Phase6IntegrationTest.database(r); ExtractedInventory.configure(r);
        broker = new EmbeddedKafkaKraftBroker(1, 3);
        broker.brokerListProperty("phase12.brokers"); broker.afterPropertiesSet();
        r.add("spring.kafka.bootstrap-servers", broker::getBrokersAsString);
        r.add("platform.messaging.order-topic", () -> TOPIC);
    }
    @AfterAll void close() {
        if (notification != null) notification.close();
        kafka.getProducerFactory().reset(); broker.destroy();
        if (notificationDb != null) notificationDb.stop();
    }
    @Test void sameTraceCrossesRealHttpDurableOutboxAndKafkaAndBacklogSignalChanges() throws Exception {
        TraceCapture.SPANS.clear();
        UUID order = UUID.randomUUID(), owner = UUID.randomUUID(), product = UUID.randomUUID();
        String number = "OBS-" + order.toString().substring(0, 20);
        jdbc.update("INSERT INTO users(id,email,password_hash) VALUES (?,?,'test')", owner, owner + "@test.com");
        jdbc.update("INSERT INTO products(id,sku,name,price,currency) VALUES (?,?,'Item',1,'USD')", product, product.toString());
        jdbc.update("INSERT INTO orders(id,customer_id,order_number,status,total,currency) VALUES (?,?,?,'CREATED',2,'USD')", order, owner, number);
        jdbc.update("INSERT INTO order_items(id,order_id,product_id,sku,name,unit_price,quantity) VALUES (?,?,?,?,'Item',1,2)", UUID.randomUUID(), order, product, product.toString());
        ExtractedInventory.jdbc().update("INSERT INTO inventory(product_id, available_quantity) VALUES (?,5)", product);
        String trace = UUID.randomUUID().toString().replace("-", "");
        String correlation = UUID.randomUUID().toString();
        var response = RestClient.create("http://localhost:" + port).post().uri("/api/orders/" + number + "/payment")
            .header("Authorization", "Bearer " + jwt.issue(owner, "CUSTOMER"))
            .header("traceparent", "00-" + trace + "-0123456789abcdef-01")
            .header("X-Correlation-ID", correlation).retrieve().toEntity(String.class);
        assertThat(response.getBody()).contains("CONFIRMED");
        assertThat(response.getHeaders().getFirst("X-Correlation-ID")).isEqualTo(correlation);
        assertThat(jdbc.queryForObject("SELECT traceparent FROM outbox_events WHERE aggregate_id = ?", String.class, order)).contains(trace);
        jdbc.update("UPDATE outbox_events SET created_at = CURRENT_TIMESTAMP - INTERVAL '90 seconds' WHERE aggregate_id = ?", order);
        databaseMetrics.poll();
        assertThat(metrics.get("platform.outbox.pending").gauge().value()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.get("platform.outbox.oldest.age").gauge().value()).isGreaterThan(60);
        // Publish while notification is offline: the group backlog must remain visible.
        for (int i = 0; i < 1000 && relay.publishNext(); i++) { }
        try (var lag = new ConsumerLagTelemetry(Map.of("bootstrap.servers", broker.getBrokersAsString()), metrics, TOPIC, GROUP)) {
            lag.poll();
            assertThat(metrics.get("platform.kafka.group.lag").tag("group", GROUP).gauge().value()).isGreaterThanOrEqualTo(1);
            startNotification();
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(notification.getBean(JdbcTemplate.class).queryForObject("SELECT count(*) FROM notification_receipts WHERE order_id = ?", Integer.class, order)).isEqualTo(1));
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                lag.poll(); assertThat(metrics.get("platform.kafka.group.lag").tag("group", GROUP).gauge().value()).isZero();
            });
        }
        databaseMetrics.poll();
        assertThat(metrics.get("platform.outbox.oldest.age").gauge().value()).isZero();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            var spans = TraceCapture.SPANS.stream().filter(s -> s.getTraceId().equals(trace)).toList();
            assertThat(spans.stream().map(s -> s.getName())).contains("order.saga", "inventory.reserve", "inventory.move", "outbox.publish", "notification.receive");
            assertThat(spans.stream().map(s -> s.getResource().getAttribute(AttributeKey.stringKey("service.name"))))
                    .contains("core-platform", "inventory-service", "notification-service");
        });
        System.out.println("TRACE_PROOF order=" + order + " order_number=" + number + " trace=" + trace + " services=core-platform,inventory-service,notification-service");
    }

    @Test void prometheusRequiresDedicatedCredentialAndHealthDoesNotExposeDetails() {
        var client = RestClient.create("http://localhost:" + port);
        assertThatThrownBy(() -> client.get().uri("/actuator/prometheus").retrieve().toBodilessEntity())
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
        assertThatThrownBy(() -> client.get().uri("/actuator/prometheus").header("Authorization", "Bearer wrong").retrieve().toBodilessEntity())
            .isInstanceOf(org.springframework.web.client.HttpClientErrorException.Unauthorized.class);
        String scrape = client.get().uri("/actuator/prometheus").header("Authorization", "Bearer local-metrics-only").retrieve().body(String.class);
        assertThat(scrape).contains("jvm_memory_used_bytes", "hikaricp_connections", "http_server_requests_seconds", "platform_outbox_pending");
        String health = client.get().uri("/actuator/health/readiness").retrieve().body(String.class);
        assertThat(health).contains("UP").doesNotContain("jdbc", "password", "components", "details");
    }

    @Test void aliveProcessCanRefuseTrafficWithoutLeakingHealthDetails() {
        var client = RestClient.create("http://localhost:" + port);
        org.springframework.boot.availability.AvailabilityChangeEvent.publish(application,
                org.springframework.boot.availability.ReadinessState.REFUSING_TRAFFIC);
        try {
            assertThat(client.get().uri("/actuator/health/liveness").retrieve().body(String.class)).contains("UP");
            assertThatThrownBy(() -> client.get().uri("/actuator/health/readiness").retrieve().toBodilessEntity())
                    .isInstanceOf(org.springframework.web.client.HttpServerErrorException.ServiceUnavailable.class);
        } finally {
            org.springframework.boot.availability.AvailabilityChangeEvent.publish(application,
                    org.springframework.boot.availability.ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    private void startNotification() {
        String url = System.getProperty("phase11.notification-url");
        String user = System.getProperty("phase11.notification-user", "platform"), password = System.getProperty("phase11.notification-password", "");
        if (url == null) {
            notificationDb = new PostgreSQLContainer<>("postgres:16-alpine"); notificationDb.start();
            url = notificationDb.getJdbcUrl(); user = notificationDb.getUsername(); password = notificationDb.getPassword();
        }
        notification = new SpringApplicationBuilder(NotificationApplication.class, TraceCapture.class).profiles("notification").run(
            "--spring.config.name=notification-test", "--spring.application.name=notification-service", "--server.port=0",
            "--spring.datasource.url=" + url, "--spring.datasource.username=" + user, "--spring.datasource.password=" + password,
            "--spring.flyway.locations=classpath:db/notification", "--service.password=test-notification",
            "--platform.messaging.enabled=true", "--platform.messaging.relay-enabled=false",
            "--platform.messaging.order-topic=" + TOPIC, "--platform.messaging.notification-group=" + GROUP,
            "--spring.kafka.bootstrap-servers=" + broker.getBrokersAsString(),
            "--spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "--spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
            "--spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "--spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
            "--spring.kafka.consumer.enable-auto-commit=false", "--spring.kafka.consumer.auto-offset-reset=earliest",
            "--spring.kafka.listener.ack-mode=record", "--spring.kafka.listener.observation-enabled=true",
            "--spring.kafka.template.observation-enabled=true", "--management.tracing.sampling.probability=1",
            "--management.otlp.tracing.export.enabled=false", "--logging.level.org.apache.kafka=WARN", "--logging.level.kafka=WARN");
    }
}
