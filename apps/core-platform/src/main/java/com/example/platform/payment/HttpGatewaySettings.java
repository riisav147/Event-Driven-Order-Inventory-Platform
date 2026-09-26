package com.example.platform.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("payment.http")
public record HttpGatewaySettings(@DefaultValue("http://localhost:8081/charges") URI url,
        @DefaultValue("200ms") Duration connectTimeout,
        @DefaultValue("750ms") Duration attemptTimeout,
        @DefaultValue("1700ms") Duration overallTimeout,
        @DefaultValue("50ms") Duration retryDelay,
        @DefaultValue("8") int maxConcurrent,
        @DefaultValue("5s") Duration openDuration) {
    public HttpGatewaySettings {
        if (url == null || !("http".equals(url.getScheme()) || "https".equals(url.getScheme()))
                || url.getHost() == null || url.getUserInfo() != null
                || connectTimeout.isZero() || connectTimeout.isNegative()
                || attemptTimeout.compareTo(connectTimeout) < 0 || overallTimeout.compareTo(attemptTimeout) < 0
                || retryDelay.isNegative() || retryDelay.compareTo(overallTimeout) >= 0
                || maxConcurrent < 1 || maxConcurrent > 100 || openDuration.isNegative() || openDuration.isZero()) {
            throw new IllegalArgumentException("Invalid payment HTTP resilience settings");
        }
    }
}
