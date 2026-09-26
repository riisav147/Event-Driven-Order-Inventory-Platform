package com.example.platform.payment;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class HttpPaymentGatewayTest {
    HttpServer server;
    ExecutorService workers;
    HttpPaymentGateway gateway;
    SimpleMeterRegistry metrics;
    CircuitBreaker breaker;
    final AtomicInteger requests = new AtomicInteger();
    final List<String> keys = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile Handler handler = exchange -> reply(exchange, 200, "{\"outcome\":\"SUCCESS\",\"reference\":\"mock-1\"}");

    @BeforeEach void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 64);
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/charges", exchange -> {
            requests.incrementAndGet();
            keys.add(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            try { handler.handle(exchange); } catch (Exception disconnected) { exchange.close(); }
        });
        server.start();
        metrics = new SimpleMeterRegistry();
        breaker = CircuitBreaker.of("test", CircuitBreakerConfig.custom().slidingWindowSize(4).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofMillis(200)).permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpPaymentGateway.RejectedRequest.class).build());
        gateway = adapter(uri(), 4, breaker);
    }

    HttpPaymentGateway adapter(URI uri, int permits, CircuitBreaker circuit) {
        return new HttpPaymentGateway(JsonMapper.builder().findAndAddModules().build(),
                new HttpGatewaySettings(uri, Duration.ofMillis(50), Duration.ofMillis(200), Duration.ofMillis(450),
                        Duration.ofMillis(10), permits, Duration.ofMillis(200)), circuit, metrics);
    }
    URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/charges"); }
    PaymentGateway.Result charge() { return gateway.charge("order:stable", BigDecimal.TEN, "USD"); }

    @AfterEach void stop() {
        gateway.close(); server.stop(0); workers.shutdownNow(); metrics.close();
    }

    @Test void transientHttpFailureRetriesIdenticalIntentAndSucceeds() {
        handler = exchange -> {
            if (requests.get() == 1) reply(exchange, 503, "{}");
            else reply(exchange, 200, "{\"outcome\":\"SUCCESS\",\"reference\":\"mock-1\"}");
        };
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.SUCCESS);
        assertThat(requests.get()).isEqualTo(2);
        assertThat(keys).containsOnly("order:stable");
        assertThat(bodies.get(0)).isEqualTo(bodies.get(1));
        assertThat(metrics.get("payment.gateway.retries").counter().count()).isEqualTo(1);
    }

    @Test void declineIsNotRetriedOrCountedAsCircuitFailure() {
        handler = exchange -> reply(exchange, 200, "{\"outcome\":\"DECLINED\",\"reason\":\"declined\"}");
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.DECLINED);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test void permanentHttpRejectionIsUnknownAndNotRetried() {
        handler = exchange -> reply(exchange, 400, "{}");
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test void malformedSuccessCannotConfirmPayment() {
        handler = exchange -> reply(exchange, 200, "{\"outcome\":\"SUCCESS\"}");
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void slowHeadersRespectOverallBudget() {
        handler = exchange -> { Thread.sleep(1000); reply(exchange, 200, "{}"); };
        long start = System.nanoTime();
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1000);
        assertThat(requests.get()).isBetween(1, 2);
        assertThat(metrics.get("payment.gateway.inflight").gauge().value()).isZero();
    }

    @Test void slowResponseBodyAlsoTimesOut() {
        handler = exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{'); exchange.getResponseBody().flush();
            Thread.sleep(1000); exchange.close();
        };
        long start = System.nanoTime();
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(1000);
    }

    @Test void overallDeadlineTruncatesTheSecondAttempt() {
        gateway.close();
        gateway = new HttpPaymentGateway(JsonMapper.builder().build(), new HttpGatewaySettings(uri(),
                Duration.ofMillis(50), Duration.ofMillis(300), Duration.ofMillis(350), Duration.ofMillis(10), 4,
                Duration.ofMillis(200)), breaker, metrics);
        handler = exchange -> { Thread.sleep(1000); reply(exchange, 200, "{}"); };
        long start = System.nanoTime();
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(600);
        assertThat(requests.get()).isBetween(1, 2);
    }

    @Test void failedHalfOpenProbesReopenTheCircuit() {
        handler = exchange -> reply(exchange, 503, "{}");
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        charge();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int probes = requests.get();
        charge();
        assertThat(requests.get()).isEqualTo(probes);
    }

    @Test void refusedConnectionHasBoundedAttempts() throws IOException {
        int port;
        try (var closed = new java.net.ServerSocket(0)) { port = closed.getLocalPort(); }
        try (var offline = adapter(URI.create("http://127.0.0.1:" + port + "/charges"), 4, breaker)) {
            assertThat(offline.charge("order:offline", BigDecimal.TEN, "USD").outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
            assertThat(metrics.get("payment.gateway.attempts").counter().count()).isEqualTo(2);
        }
    }

    @Test void circuitOpensRejectsThenRecoversThroughHalfOpen() {
        handler = exchange -> reply(exchange, 503, "{}");
        charge(); charge();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int sent = requests.get();
        assertThat(charge().reason()).contains("CIRCUIT_OPEN");
        assertThat(requests.get()).isEqualTo(sent);
        handler = exchange -> reply(exchange, 200, "{\"outcome\":\"SUCCESS\",\"reference\":\"recovered\"}");
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.SUCCESS));
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(charge().outcome()).isEqualTo(PaymentGateway.Outcome.SUCCESS);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test void concurrentSlowGatewayBurstDemonstratesResourceIsolation() throws Exception {
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        handler = exchange -> {
            peak.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { Thread.sleep(700); reply(exchange, 503, "{}"); }
            finally { active.decrementAndGet(); }
        };
        int baselinePeak;
        try (var raw = HttpClient.newHttpClient()) {
            var batch = new ArrayList<CompletableFuture<HttpResponse<String>>>();
            for (int i = 0; i < 40; i++) batch.add(raw.sendAsync(HttpRequest.newBuilder(uri()).timeout(Duration.ofSeconds(3))
                    .header("Idempotency-Key", "baseline").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString()));
            CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            baselinePeak = peak.get();
        }
        requests.set(0); peak.set(0);
        CountDownLatch go = new CountDownLatch(1), ready = new CountDownLatch(40);
        long start;
        try (var callers = Executors.newFixedThreadPool(40)) {
            var futures = new ArrayList<Future<PaymentGateway.Result>>();
            for (int i = 0; i < 40; i++) futures.add(callers.submit(() -> { ready.countDown(); go.await(); return charge(); }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start = System.nanoTime(); go.countDown();
            for (var future : futures) assertThat(future.get(2, TimeUnit.SECONDS).outcome()).isEqualTo(PaymentGateway.Outcome.TIMEOUT);
        }
        long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
        assertThat(requests.get()).isLessThanOrEqualTo(8);
        assertThat(peak.get()).isLessThan(baselinePeak);
        assertThat(metrics.get("payment.gateway.pending").tag("reason", "BULKHEAD_FULL").counter().count()).isGreaterThanOrEqualTo(36);
        assertThat(elapsed).isLessThan(1500);
        System.out.printf("GATEWAY_BURST callers=40 baselinePeak=%d protectedPeak=%d protectedRequests=%d elapsedMs=%d%n",
                baselinePeak, peak.get(), requests.get(), elapsed);
    }

    static void reply(HttpExchange exchange, int code, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    interface Handler { void handle(HttpExchange exchange) throws Exception; }
}
