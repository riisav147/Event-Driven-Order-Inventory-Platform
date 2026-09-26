package com.example.gateway;

import com.example.platform.payment.HttpPaymentGateway.ChargeRequest;
import com.example.platform.payment.MockPaymentGateway;
import com.example.platform.payment.PaymentGateway;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.*;

/** Local-only simulated provider. Separate process; same durable simulation ledger as Phase 7. */
public final class MockGatewayServer implements AutoCloseable {
    public enum Fault { NONE, HTTP_500, DELAY, DELAY_AFTER_CHARGE }
    private final HttpServer server;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), new ThreadPoolExecutor.AbortPolicy());
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();

    public MockGatewayServer(InetSocketAddress address, PaymentGateway ledger, Fault fault, long delayMs) throws IOException {
        server = HttpServer.create(address, 32);
        server.setExecutor(workers);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"UP\"}"));
        server.createContext("/charges", exchange -> {
            try {
                if (!"/charges".equals(exchange.getRequestURI().getPath()) || !"POST".equals(exchange.getRequestMethod())) {
                    respond(exchange, 405, "{}"); return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(8193);
                if (bytes.length > 8192) { respond(exchange, 413, "{}"); return; }
                ChargeRequest request = mapper.readValue(bytes, ChargeRequest.class);
                if (request == null || request.idempotencyKey() == null || request.idempotencyKey().isBlank()
                        || request.idempotencyKey().length() > 80 || request.amount() == null || request.amount().signum() < 0
                        || request.currency() == null || !request.currency().matches("[A-Z]{3}")
                        || !request.idempotencyKey().equals(exchange.getRequestHeaders().getFirst("Idempotency-Key"))) {
                    respond(exchange, 400, "{}"); return;
                }
                if (fault == Fault.HTTP_500) { respond(exchange, 503, "{}"); return; }
                if (fault == Fault.DELAY) Thread.sleep(delayMs);
                var result = ledger.charge(request.idempotencyKey(), request.amount(), request.currency());
                if (fault == Fault.DELAY_AFTER_CHARGE) Thread.sleep(delayMs);
                respond(exchange, 200, mapper.writeValueAsString(result));
            } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
                respond(exchange, 400, "{}");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                exchange.close();
            } catch (Exception unavailable) {
                try { respond(exchange, 503, "{}"); } catch (IOException disconnected) { exchange.close(); }
            }
        });
        server.start();
    }

    private void respond(HttpExchange exchange, int code, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    public int port() { return server.getAddress().getPort(); }
    @Override public void close() { server.stop(0); workers.shutdownNow(); }

    public static void main(String[] args) throws Exception {
        String url = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5435/gateway");
        String user = System.getenv().getOrDefault("DB_USERNAME", "platform");
        String password = System.getenv("DB_PASSWORD");
        if (password == null) throw new IllegalArgumentException("DB_PASSWORD is required for the mock gateway");
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        jdbc.execute("CREATE TABLE IF NOT EXISTS mock_gateway_charges (idempotency_key VARCHAR(80) PRIMARY KEY, "
                + "amount NUMERIC(12,2) NOT NULL, currency CHAR(3) NOT NULL, outcome VARCHAR(16) NOT NULL "
                + "CHECK (outcome IN ('SUCCESS','DECLINED','ERROR')), reference VARCHAR(80) NOT NULL UNIQUE)");
        var scenario = PaymentGateway.Outcome.valueOf(System.getenv().getOrDefault("MOCK_GATEWAY_OUTCOME", "SUCCESS"));
        var fault = Fault.valueOf(System.getenv().getOrDefault("MOCK_GATEWAY_FAULT", "NONE"));
        var server = new MockGatewayServer(new InetSocketAddress(System.getenv().getOrDefault("MOCK_GATEWAY_BIND", "127.0.0.1"),
                Integer.parseInt(System.getenv().getOrDefault("MOCK_GATEWAY_PORT", "8081"))),
                new MockPaymentGateway(jdbc, scenario), fault,
                Long.parseLong(System.getenv().getOrDefault("MOCK_GATEWAY_DELAY_MS", "2000")));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        System.out.println("Local simulated payment gateway listening on port " + server.port());
    }
}
