package com.example.platform.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "payment.gateway", havingValue = "http")
@EnableConfigurationProperties(HttpGatewaySettings.class)
public class HttpGatewayConfiguration {
    @Bean
    CircuitBreakerRegistry paymentCircuitBreakers(HttpGatewaySettings settings, MeterRegistry metrics) {
        var registry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(10).minimumNumberOfCalls(5).failureRateThreshold(50)
                .waitDurationInOpenState(settings.openDuration()).permittedNumberOfCallsInHalfOpenState(2)
                .ignoreExceptions(HttpPaymentGateway.RejectedRequest.class)
                .build());
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(metrics);
        return registry;
    }

    @Bean
    HttpPaymentGateway httpPaymentGateway(ObjectMapper mapper, HttpGatewaySettings settings,
                                          CircuitBreakerRegistry breakers, MeterRegistry metrics) {
        return new HttpPaymentGateway(mapper, settings, breakers.circuitBreaker("paymentGateway"), metrics);
    }
}
