package com.example.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class InventoryCommands {
    private final JdbcTemplate jdbc;
    public InventoryCommands(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Item(UUID productId, int quantity) { }
    public record Result(String status) { }

    @Transactional
    public void stock(UUID product, int quantity) {
        if (quantity < 0) throw new IllegalArgumentException("quantity must be nonnegative");
        jdbc.update("INSERT INTO inventory(product_id, available_quantity) VALUES (?, ?) "
                + "ON CONFLICT(product_id) DO UPDATE SET available_quantity = EXCLUDED.available_quantity", product, quantity);
    }

    @Transactional
    public Result reserve(UUID order, List<Item> supplied) {
        if (supplied == null || supplied.isEmpty() || supplied.size() > 1000
                || supplied.stream().anyMatch(i -> i == null || i.productId() == null || i.quantity() <= 0))
            throw new IllegalArgumentException("one to 1000 positive, distinct product quantities required");
        var items = supplied.stream().sorted(Comparator.comparing(Item::productId)).toList();
        if (items.stream().map(Item::productId).distinct().count() != items.size())
            throw new IllegalArgumentException("duplicate product");
        String fingerprint = items.toString();
        jdbc.update("INSERT INTO reservation_orders(order_id, request, status) VALUES (?, ?, 'RESERVED') "
                + "ON CONFLICT DO NOTHING", order, fingerprint);
        var previous = jdbc.queryForMap("SELECT request, status FROM reservation_orders WHERE order_id = ? FOR UPDATE", order);
        if (!fingerprint.equals(previous.get("request"))) throw new IllegalArgumentException("order id reused with different items");
        String status = (String) previous.get("status");
        if (!"RESERVED".equals(status) || Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM reservations WHERE order_id = ?)", Boolean.class, order))) return new Result(status);

        // Lock in a stable order and validate the entire basket before changing any stock.
        boolean enough = true;
        for (Item item : items) {
            var quantities = jdbc.query("SELECT available_quantity FROM inventory WHERE product_id = ? FOR UPDATE",
                    (rs, n) -> rs.getInt(1), item.productId());
            if (quantities.isEmpty() || quantities.getFirst() < item.quantity()) enough = false;
        }
        if (!enough) {
            jdbc.update("UPDATE reservation_orders SET status = 'REJECTED' WHERE order_id = ?", order);
            return new Result("REJECTED");
        }
        for (Item item : items) {
            jdbc.update("UPDATE inventory SET available_quantity = available_quantity - ?, reserved_quantity = reserved_quantity + ? WHERE product_id = ?",
                    item.quantity(), item.quantity(), item.productId());
            jdbc.update("INSERT INTO reservations(order_id, product_id, quantity) VALUES (?, ?, ?)", order, item.productId(), item.quantity());
        }
        return new Result("RESERVED");
    }

    @Transactional
    public Result move(UUID order, String target) {
        if (!Set.of("CONFIRMED", "RELEASED").contains(target)) throw new IllegalArgumentException("invalid transition");
        String current = jdbc.queryForObject("SELECT status FROM reservation_orders WHERE order_id = ? FOR UPDATE", String.class, order);
        if (target.equals(current)) return new Result(current);
        if (!"RESERVED".equals(current)) throw new IllegalStateException("reservation already " + current);
        var items = jdbc.query("SELECT product_id, quantity FROM reservations WHERE order_id = ? ORDER BY product_id",
                (rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getInt(2)), order);
        // Use the same UUID comparator as reservation to avoid inverted lock ordering.
        items = items.stream().sorted(Comparator.comparing(Item::productId)).toList();
        for (Item item : items) {
            jdbc.update("UPDATE inventory SET reserved_quantity = reserved_quantity - ?, available_quantity = available_quantity + ? WHERE product_id = ?",
                    item.quantity(), "RELEASED".equals(target) ? item.quantity() : 0, item.productId());
        }
        jdbc.update("UPDATE reservation_orders SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE order_id = ?", target, order);
        return new Result(target);
    }
}
