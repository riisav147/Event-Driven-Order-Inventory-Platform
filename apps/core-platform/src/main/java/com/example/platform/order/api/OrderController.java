package com.example.platform.order.api;

import com.example.platform.order.application.OrderService;
import com.example.platform.order.application.CheckoutService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.List;

import static com.example.platform.order.api.OrderDtos.OrderResponse;
import com.example.platform.shared.security.OwnershipGuard;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final OrderService orders;
    private final CheckoutService checkout;
    public OrderController(OrderService orders, CheckoutService checkout) {
        this.orders = orders;
        this.checkout = checkout;
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse checkout(@RequestHeader("X-Customer-Id") UUID customerId,
                                  @RequestHeader("Idempotency-Key") String key) {
        return checkout.checkout(customerId, key);
    }
    @GetMapping
    public List<OrderResponse> list(@RequestHeader("X-Customer-Id") UUID customerId) { OwnershipGuard.check(customerId); return orders.list(customerId); }
    @GetMapping("/{orderNumber}")
    public OrderResponse get(@RequestHeader("X-Customer-Id") UUID customerId,
                             @PathVariable("orderNumber") String orderNumber) { OwnershipGuard.check(customerId); return orders.get(customerId, orderNumber); }
}
