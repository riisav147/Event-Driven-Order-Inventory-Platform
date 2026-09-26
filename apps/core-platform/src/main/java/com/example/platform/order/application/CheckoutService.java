package com.example.platform.order.application;

import com.example.platform.order.api.OrderDtos.OrderResponse;
import com.example.platform.shared.security.OwnershipGuard;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class CheckoutService {
    private final JdbcTemplate jdbc;
    private final OrderService orders;
    private final ObjectMapper json;

    public CheckoutService(JdbcTemplate jdbc, OrderService orders, ObjectMapper json) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.json = json;
    }

    @Transactional
    public OrderResponse checkout(UUID owner, String key) {
        OwnershipGuard.check(owner);
        if (key == null || key.isBlank() || key.length() > 200) {
            throw new IllegalArgumentException("Idempotency-Key must contain 1 to 200 characters");
        }
        // This endpoint has no body. A key identifies the command, not the mutable cart contents.
        String hash = fingerprint("POST /api/orders:v1:" + owner);
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys(id, owner_id, idempotency_key, request_hash, status, expires_at)
                VALUES (?, ?, ?, ?, 'IN_PROGRESS', CURRENT_TIMESTAMP + INTERVAL '24 hours')
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """, UUID.randomUUID(), owner, key, hash);
        if (inserted == 0) {
            var saved = jdbc.queryForObject("""
                    SELECT request_hash, status, response_json, expires_at <= CURRENT_TIMESTAMP AS expired
                    FROM idempotency_keys WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE
                    """, (rs, row) -> new Saved(rs.getString("request_hash"), rs.getString("status"),
                    rs.getString("response_json"), rs.getBoolean("expired")), owner, key);
            if (!hash.equals(saved.hash())) throw new IllegalStateException("Idempotency key request mismatch");
            if (saved.expired()) throw new IllegalStateException("Idempotency key expired; use a new key");
            if (!"COMPLETED".equals(saved.status())) throw new IllegalStateException("Checkout is in progress");
            try {
                return json.readValue(saved.response(), OrderResponse.class);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Stored checkout response could not be read", e);
            }
        }
        OrderResponse response = orders.checkout(owner);
        try {
            jdbc.update("""
                    UPDATE idempotency_keys SET status = 'COMPLETED', response_order_id = ?, response_json = ?
                    WHERE owner_id = ? AND idempotency_key = ?
                    """, response.id(), json.writeValueAsString(response), owner, key);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Checkout response could not be stored", e);
        }
        return response;
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Saved(String hash, String status, String response, boolean expired) { }
}
