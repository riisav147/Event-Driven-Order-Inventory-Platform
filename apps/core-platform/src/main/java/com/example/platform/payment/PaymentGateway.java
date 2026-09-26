package com.example.platform.payment;

import java.math.BigDecimal;

public interface PaymentGateway {
    Result charge(String idempotencyKey, BigDecimal amount, String currency);

    enum Outcome { SUCCESS, DECLINED, TIMEOUT, ERROR }
    record Result(Outcome outcome, String reference, String reason) { }
}
