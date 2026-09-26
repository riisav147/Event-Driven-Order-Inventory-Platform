package com.example.platform.order.domain;

public class InvalidOrderTransitionException extends RuntimeException {

    public InvalidOrderTransitionException(OrderStatus from, OrderStatus to) {
        super("Order cannot transition from " + from + " to " + to);
    }
}
