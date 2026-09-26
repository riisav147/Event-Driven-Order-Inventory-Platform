package com.example.platform.order.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class Order {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = transitions();

    private OrderStatus status;

    public Order() {
        this(OrderStatus.CREATED);
    }

    public Order(OrderStatus status) {
        this.status = java.util.Objects.requireNonNull(status);
    }

    public OrderStatus status() {
        return status;
    }

    public void reserveInventory() {
        transitionTo(OrderStatus.RESERVED);
    }

    public void recordPaymentFailure() {
        transitionTo(OrderStatus.PAYMENT_FAILED);
    }

    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    public void confirm() {
        transitionTo(OrderStatus.CONFIRMED);
    }

    public void cancel() {
        transitionTo(OrderStatus.CANCELLED);
    }

    private void transitionTo(OrderStatus next) {
        if (!ALLOWED_TRANSITIONS.get(status).contains(next)) {
            throw new InvalidOrderTransitionException(status, next);
        }
        status = next;
    }

    private static Map<OrderStatus, Set<OrderStatus>> transitions() {
        Map<OrderStatus, Set<OrderStatus>> result = new EnumMap<>(OrderStatus.class);
        result.put(OrderStatus.CREATED, EnumSet.of(OrderStatus.RESERVED, OrderStatus.CANCELLED));
        result.put(OrderStatus.RESERVED, EnumSet.of(OrderStatus.PAID, OrderStatus.PAYMENT_FAILED, OrderStatus.CANCELLED));
        result.put(OrderStatus.PAYMENT_FAILED, EnumSet.of(OrderStatus.CANCELLED));
        result.put(OrderStatus.PAID, EnumSet.of(OrderStatus.CONFIRMED));
        result.put(OrderStatus.CONFIRMED, EnumSet.noneOf(OrderStatus.class));
        result.put(OrderStatus.CANCELLED, EnumSet.noneOf(OrderStatus.class));
        return Map.copyOf(result);
    }
}
