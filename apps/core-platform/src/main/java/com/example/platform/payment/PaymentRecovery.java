package com.example.platform.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecovery {
    private final PaymentService payments;
    public PaymentRecovery(PaymentService payments) { this.payments = payments; }
    @Scheduled(fixedDelayString = "${payment.recovery.interval-ms:5000}")
    public void poll() { payments.recover(); }
}
