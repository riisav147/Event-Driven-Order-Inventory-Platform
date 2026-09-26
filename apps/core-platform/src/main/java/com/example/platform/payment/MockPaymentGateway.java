package com.example.platform.payment;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.UUID;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "payment.gateway", havingValue = "local", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {
    private final JdbcTemplate jdbc;
    private final Outcome scenario;

    public MockPaymentGateway(JdbcTemplate jdbc, @Value("${payment.mock-outcome:SUCCESS}") Outcome scenario) {
        this.jdbc = jdbc;
        this.scenario = scenario;
    }

    @Override
    public Result charge(String key, BigDecimal amount, String currency) {
        // TIMEOUT models a successful charge whose response was lost. A same-key retry reconciles it.
        Outcome outcome = scenario == Outcome.TIMEOUT ? Outcome.SUCCESS : scenario;
        int inserted = jdbc.update("INSERT INTO mock_gateway_charges(idempotency_key, amount, currency, outcome, reference) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                key, amount, currency, outcome.name(), "mock-" + UUID.randomUUID());
        Result saved = jdbc.queryForObject("SELECT * FROM mock_gateway_charges WHERE idempotency_key = ?", (rs, row) -> {
            if (amount.compareTo(rs.getBigDecimal("amount")) != 0 || !currency.equals(rs.getString("currency"))) {
                throw new IllegalArgumentException("payment intent amount or currency mismatch");
            }
            Outcome result = Outcome.valueOf(rs.getString("outcome"));
            return new Result(result, rs.getString("reference"), switch (result) {
                case DECLINED -> "Simulated payment decline";
                case ERROR -> "Simulated failure before charge";
                default -> null;
            });
        }, key);
        return inserted == 1 && scenario == Outcome.TIMEOUT
                ? new Result(Outcome.TIMEOUT, null, "Provider outcome unknown; retry the same intent") : saved;
    }
}
