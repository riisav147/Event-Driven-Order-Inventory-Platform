package com.example.platform.cart.application;

import com.example.platform.cart.api.CartDtos.AddItemRequest;
import com.example.platform.cart.api.CartDtos.CartItemResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class CartService {
    private final JdbcTemplate jdbc;
    public CartService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Transactional
    public List<CartItemResponse> add(UUID customerId, AddItemRequest request) {
        if (request.quantity() <= 0) throw new IllegalArgumentException("quantity must be positive");
        UUID cartId = findOrCreateCart(customerId);
        jdbc.update("INSERT INTO cart_items(id, cart_id, product_id, quantity) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (cart_id, product_id) DO UPDATE SET quantity = cart_items.quantity + EXCLUDED.quantity",
                UUID.randomUUID(), cartId, request.productId(), request.quantity());
        return get(customerId);
    }
    public List<CartItemResponse> get(UUID customerId) {
        return jdbc.query("SELECT p.id, p.sku, p.name, ci.quantity, p.price FROM cart_items ci "
                        + "JOIN carts c ON c.id = ci.cart_id JOIN products p ON p.id = ci.product_id "
                        + "WHERE c.customer_id = ? AND c.status = 'ACTIVE' ORDER BY p.name",
                (rs, row) -> new CartItemResponse(rs.getObject("id", UUID.class), rs.getString("sku"),
                        rs.getString("name"), rs.getInt("quantity"), rs.getBigDecimal("price")), customerId);
    }
    @Transactional
    public List<CartItemResponse> update(UUID customerId, UUID productId, int quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        UUID cartId = findOrCreateCart(customerId);
        int changed = jdbc.update("UPDATE cart_items SET quantity = ? WHERE cart_id = ? AND product_id = ?",
                quantity, cartId, productId);
        if (changed == 0) throw new IllegalStateException("cart item was not found");
        return get(customerId);
    }
    @Transactional
    public List<CartItemResponse> remove(UUID customerId, UUID productId) {
        UUID cartId = findOrCreateCart(customerId);
        jdbc.update("DELETE FROM cart_items WHERE cart_id = ? AND product_id = ?", cartId, productId);
        return get(customerId);
    }
    public UUID findOrCreateCart(UUID customerId) {
        List<UUID> ids = jdbc.query("SELECT id FROM carts WHERE customer_id = ? AND status = 'ACTIVE'",
                (rs, row) -> rs.getObject("id", UUID.class), customerId);
        if (!ids.isEmpty()) return ids.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO carts(id, customer_id) VALUES (?, ?)", id, customerId);
        return id;
    }
}
