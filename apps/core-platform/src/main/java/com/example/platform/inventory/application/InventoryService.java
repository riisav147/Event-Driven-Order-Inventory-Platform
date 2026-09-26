package com.example.platform.inventory.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** API adapter only: core has no access to inventory-owned tables. */
@Service
public class InventoryService {
    private final RestClient client;
    public InventoryService(@Value("${inventory.url:http://localhost:8082}") String url,
                            @Value("${inventory.password:local-inventory-only}") String password,
                            RestClient.Builder builder) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build());
        factory.setReadTimeout(Duration.ofSeconds(2));
        client = builder.baseUrl(url).requestFactory(factory)
                .defaultHeaders(h -> h.setBasicAuth("core", password))
                .requestInterceptor((request, body, execution) -> {
                    String correlation = org.slf4j.MDC.get("correlation_id");
                    String order = org.slf4j.MDC.get("order_id");
                    if (correlation != null) request.getHeaders().set("X-Correlation-ID", correlation);
                    if (order != null) request.getHeaders().set("X-Order-ID", order);
                    return execution.execute(request, body);
                }).build();
    }
    public record Item(UUID productId, int quantity) { }
    public record Result(String status) { }
    public void setStock(UUID productId, int quantity) {
        client.put().uri("/internal/v1/stock/{id}", productId)
                .body(java.util.Map.of("quantity", quantity)).retrieve().toBodilessEntity();
    }
    public Result reserve(UUID orderId, List<Item> items) {
        return required(client.put().uri("/internal/v1/reservations/{id}", orderId).body(items).retrieve().body(Result.class));
    }
    public Result move(UUID orderId, String target) {
        return required(client.post().uri("/internal/v1/reservations/{id}/{target}", orderId, target).retrieve().body(Result.class));
    }
    private Result required(Result result) {
        if (result == null || result.status() == null) throw new IllegalStateException("Missing inventory response");
        return result;
    }
}
