package com.example.platform.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.*;

public class HttpPaymentGateway implements PaymentGateway, AutoCloseable {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final HttpGatewaySettings settings;
    private final CircuitBreaker breaker;
    private final MeterRegistry metrics;
    private final Semaphore capacity;
    @org.springframework.beans.factory.annotation.Autowired
    private com.example.telemetry.FlowTelemetry telemetry = new com.example.telemetry.FlowTelemetry();

    public HttpPaymentGateway(ObjectMapper mapper, HttpGatewaySettings settings, CircuitBreaker breaker, MeterRegistry metrics) {
        this.mapper = mapper;
        this.settings = settings;
        this.breaker = breaker;
        this.metrics = metrics;
        this.capacity = new Semaphore(settings.maxConcurrent());
        this.client = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
        metrics.gauge("payment.gateway.inflight", capacity, s -> settings.maxConcurrent() - s.availablePermits());
    }

    @Override
    public Result charge(String key, BigDecimal amount, String currency) {
        try (var scope = telemetry.open("payment.gateway", null, null, null, null)) {
            Result result = chargeObserved(key, amount, currency);
            scope.outcome(result.outcome().name());
            return result;
        }
    }

    private Result chargeObserved(String key, BigDecimal amount, String currency) {
        Timer.Sample sample = Timer.start(metrics);
        if (!capacity.tryAcquire()) {
            sample.stop(metrics.timer("payment.gateway.duration", "outcome", "bulkhead"));
            return pending("BULKHEAD_FULL");
        }
        String outcome = "unknown";
        long deadline = System.nanoTime() + settings.overallTimeout().toNanos();
        try {
            String body = mapper.writeValueAsString(new ChargeRequest(key, amount, currency));
            for (int attempt = 0; attempt < 2; attempt++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return pending("OVERALL_TIMEOUT");
                try {
                    Duration timeout = Duration.ofNanos(Math.min(remaining, settings.attemptTimeout().toNanos()));
                    Result result = breaker.executeSupplier(() -> send(key, body, timeout));
                    outcome = result.outcome().name().toLowerCase(java.util.Locale.ROOT);
                    metrics.counter("payment.gateway.results", "outcome", outcome).increment();
                    return result;
                } catch (CallNotPermittedException open) {
                    return pending("CIRCUIT_OPEN");
                } catch (RejectedRequest permanent) {
                    return pending("HTTP_REQUEST_REJECTED");
                } catch (GatewayFailure transientFailure) {
                    if (Thread.currentThread().isInterrupted()) return pending("INTERRUPTED");
                    if (breaker.getState() == CircuitBreaker.State.OPEN) return pending("CIRCUIT_OPEN");
                    long delay = settings.retryDelay().toNanos();
                    long pause = delay == 0 ? 0 : delay + ThreadLocalRandom.current().nextLong(delay + 1);
                    if (attempt == 1 || deadline - System.nanoTime() <= pause) return pending(transientFailure.getMessage());
                    // A probe failure must not consume a second half-open permit in the same logical call.
                    if (breaker.getState() == CircuitBreaker.State.HALF_OPEN) return pending(transientFailure.getMessage());
                    TimeUnit.NANOSECONDS.sleep(pause);
                    metrics.counter("payment.gateway.retries").increment();
                }
            }
            return pending("OVERALL_TIMEOUT");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return pending("INTERRUPTED");
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            return pending("SERIALIZATION_ERROR");
        } finally {
            capacity.release();
            sample.stop(metrics.timer("payment.gateway.duration", "outcome", outcome));
        }
    }

    private Result send(String key, String body, Duration timeout) {
        metrics.counter("payment.gateway.attempts").increment();
        var builder = HttpRequest.newBuilder(settings.url()).timeout(timeout)
                .header("Content-Type", "application/json").header("Idempotency-Key", key)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        telemetry.headers().forEach(builder::header);
        String correlation = org.slf4j.MDC.get("correlation_id");
        if (correlation != null) builder.header("X-Correlation-ID", correlation);
        var request = builder.build();
        var future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // Covers the entire response body, not just receipt of HTTP headers.
            var response = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            if (response.statusCode() >= 500 || response.statusCode() == 408 || response.statusCode() == 429) {
                throw new GatewayFailure("HTTP_TRANSIENT");
            }
            if (response.statusCode() != 200) throw new RejectedRequest();
            Result result;
            try { result = mapper.readValue(response.body(), Result.class); }
            catch (Exception malformed) { throw new RejectedRequest(); }
            if (result == null || result.outcome() == null
                    || (result.reference() != null && result.reference().length() > 80)
                    || (result.outcome() == Outcome.SUCCESS && (result.reference() == null || result.reference().isBlank()))) {
                throw new RejectedRequest();
            }
            if (result.outcome() == Outcome.TIMEOUT) throw new GatewayFailure("PROVIDER_UNKNOWN");
            String reason = result.reason();
            return new Result(result.outcome(), result.reference(), reason != null && reason.length() > 160 ? reason.substring(0, 160) : reason);
        } catch (TimeoutException timeoutFailure) {
            future.cancel(true);
            throw new GatewayFailure("ATTEMPT_TIMEOUT");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new GatewayFailure("INTERRUPTED");
        } catch (ExecutionException unavailable) {
            throw new GatewayFailure("TRANSPORT_FAILURE");
        }
    }

    private Result pending(String reason) {
        metrics.counter("payment.gateway.pending", "reason", reason).increment();
        return new Result(Outcome.TIMEOUT, null, reason + ": retry the same payment intent");
    }

    @Override public void close() { client.shutdownNow(); }
    public record ChargeRequest(String idempotencyKey, BigDecimal amount, String currency) { }
    public static final class GatewayFailure extends RuntimeException {
        public GatewayFailure(String reason) { super(reason); }
    }
    public static final class RejectedRequest extends RuntimeException { }
}
