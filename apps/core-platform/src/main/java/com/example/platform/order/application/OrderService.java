package com.example.platform.order.application;

import com.example.platform.order.api.OrderDtos.OrderResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class OrderService {
    private final JdbcTemplate jdbc;
    public OrderService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public OrderResponse checkout(UUID customerId) {
        UUID cartId = jdbc.queryForObject("SELECT id FROM carts WHERE customer_id = ? AND status = 'ACTIVE' FOR UPDATE",
                UUID.class, customerId);
        List<CartLine> lines = jdbc.query("SELECT p.id, p.sku, p.name, p.price, p.currency, p.enabled, ci.quantity FROM cart_items ci "
                        + "JOIN products p ON p.id = ci.product_id WHERE ci.cart_id = ?",
                (rs, row) -> new CartLine(rs.getObject("id", UUID.class), rs.getString("sku"), rs.getString("name"), rs.getBigDecimal("price"),
                        rs.getString("currency"), rs.getInt("quantity"), rs.getBoolean("enabled")), cartId);
        if (lines.isEmpty()) throw new IllegalStateException("cart is empty");
        String currency = lines.get(0).currency();
        if (lines.stream().anyMatch(line -> !line.enabled() || !currency.equals(line.currency()))) {
            throw new IllegalStateException("cart contains disabled products or mixed currencies");
        }
        BigDecimal total = lines.stream().map(line -> line.price().multiply(BigDecimal.valueOf(line.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID orderId = UUID.randomUUID();
        String orderNumber = "ORD-" + orderId.toString().substring(0, 8).toUpperCase();
        jdbc.update("INSERT INTO orders(id, order_number, customer_id, status, total, currency) VALUES (?, ?, ?, 'CREATED', ?, ?)",
                orderId, orderNumber, customerId, total, currency);
        for (CartLine line : lines) {
            jdbc.update("INSERT INTO order_items(id, order_id, product_id, sku, name, unit_price, quantity) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), orderId, line.productId(), line.sku(), line.name(), line.price(), line.quantity());
        }
        jdbc.update("INSERT INTO order_status_history(order_id, status) VALUES (?, 'CREATED')", orderId);
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ?", cartId);
        return new OrderResponse(orderId, orderNumber, "CREATED", total, currency);
    }

    public OrderResponse get(UUID customerId, String orderNumber) {
        return jdbc.queryForObject("SELECT id, order_number, status, total, currency FROM orders "
                        + "WHERE customer_id = ? AND order_number = ?",
                (rs, row) -> new OrderResponse(rs.getObject("id", UUID.class), rs.getString("order_number"),
                        rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency")),
                customerId, orderNumber);
    }
    public List<OrderResponse> list(UUID customerId) {
        return jdbc.query("SELECT id, order_number, status, total, currency FROM orders "
                        + "WHERE customer_id = ? ORDER BY created_at DESC",
                (rs, row) -> new OrderResponse(rs.getObject("id", UUID.class), rs.getString("order_number"),
                        rs.getString("status"), rs.getBigDecimal("total"), rs.getString("currency")), customerId);
    }

    private record CartLine(UUID productId, String sku, String name, BigDecimal price, String currency, int quantity, boolean enabled) { }
}
