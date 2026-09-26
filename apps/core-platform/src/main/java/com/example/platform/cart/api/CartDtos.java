package com.example.platform.cart.api;

import java.math.BigDecimal;
import java.util.UUID;
import jakarta.validation.constraints.Min;

public final class CartDtos {
    private CartDtos() { }
    public record AddItemRequest(UUID productId, @Min(1) int quantity) { }
    public record UpdateItemRequest(@Min(1) int quantity) { }
    public record CartItemResponse(UUID productId, String sku, String name, int quantity, BigDecimal unitPrice) { }
}
