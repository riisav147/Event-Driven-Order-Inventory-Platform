package com.example.platform.order.api;

import java.math.BigDecimal;
import java.util.UUID;

public final class OrderDtos {
    private OrderDtos() { }
    public record OrderResponse(UUID id, String orderNumber, String status, BigDecimal total, String currency) { }
}
