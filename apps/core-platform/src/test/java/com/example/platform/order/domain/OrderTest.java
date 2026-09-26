package com.example.platform.order.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(OrderStatus.class)
    void restoredStatesEnforceEveryTransition(OrderStatus start) {
        for (OrderStatus target : OrderStatus.values()) {
            if (target == OrderStatus.CREATED) continue;
            Order order = new Order(start);
            Runnable change = switch (target) {
                case RESERVED -> order::reserveInventory;
                case PAID -> order::markPaid;
                case CONFIRMED -> order::confirm;
                case PAYMENT_FAILED -> order::recordPaymentFailure;
                case CANCELLED -> order::cancel;
                default -> throw new AssertionError();
            };
            boolean allowed = switch (start) {
                case CREATED -> target == OrderStatus.RESERVED || target == OrderStatus.CANCELLED;
                case RESERVED -> target == OrderStatus.PAID || target == OrderStatus.PAYMENT_FAILED || target == OrderStatus.CANCELLED;
                case PAID -> target == OrderStatus.CONFIRMED;
                case PAYMENT_FAILED -> target == OrderStatus.CANCELLED;
                default -> false;
            };
            if (allowed) {
                change.run();
                assertThat(order.status()).isEqualTo(target);
            } else {
                assertThatThrownBy(change::run).isInstanceOf(InvalidOrderTransitionException.class);
                assertThat(order.status()).isEqualTo(start);
            }
        }
    }

    @Test
    void followsTheHappyPath() {
        Order order = new Order();

        order.reserveInventory();
        order.markPaid();
        order.confirm();

        assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    void rejectsSkippingStates() {
        Order order = new Order();

        assertThatThrownBy(order::confirm)
                .isInstanceOf(InvalidOrderTransitionException.class);
    }

    @Test
    void recordsPaymentFailureBeforeCancellation() {
        Order order = new Order();
        order.reserveInventory();

        order.recordPaymentFailure();
        order.cancel();

        assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
    }
}
