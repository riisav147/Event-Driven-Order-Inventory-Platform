package com.example.platform.cart.api;

import com.example.platform.cart.application.CartService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static com.example.platform.cart.api.CartDtos.AddItemRequest;
import static com.example.platform.cart.api.CartDtos.CartItemResponse;
import static com.example.platform.cart.api.CartDtos.UpdateItemRequest;
import com.example.platform.shared.security.OwnershipGuard;

@RestController
@RequestMapping("/api/cart")
public class CartController {
    private final CartService carts;
    public CartController(CartService carts) { this.carts = carts; }
    @GetMapping
    public List<CartItemResponse> get(@RequestHeader("X-Customer-Id") UUID customerId) { OwnershipGuard.check(customerId); return carts.get(customerId); }
    @PostMapping("/items")
    public List<CartItemResponse> add(@RequestHeader("X-Customer-Id") UUID customerId,
                                      @jakarta.validation.Valid @RequestBody AddItemRequest request) { OwnershipGuard.check(customerId); return carts.add(customerId, request); }
    @PatchMapping("/items/{productId}")
    public List<CartItemResponse> update(@RequestHeader("X-Customer-Id") UUID customerId,
                                         @PathVariable UUID productId,
                                         @jakarta.validation.Valid @RequestBody UpdateItemRequest request) {
        OwnershipGuard.check(customerId); return carts.update(customerId, productId, request.quantity());
    }
    @DeleteMapping("/items/{productId}")
    public List<CartItemResponse> remove(@RequestHeader("X-Customer-Id") UUID customerId,
                                         @PathVariable UUID productId) { OwnershipGuard.check(customerId); return carts.remove(customerId, productId); }
}
