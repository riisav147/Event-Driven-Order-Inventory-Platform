package com.example.telemetry;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@EnableScheduling
public class TelemetryConfiguration {
    @Bean FlowTelemetry flowTelemetry(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        return new FlowTelemetry(tracer, propagator);
    }
    @Bean FilterRegistrationBean<RequestTelemetryFilter> telemetryFilter(@Value("${platform.telemetry.metrics-token:local-metrics-only}") String token) {
        var registration = new FilterRegistrationBean<>(new RequestTelemetryFilter(token));
        // Inside the server observation but before Spring Security.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
    @Bean
    @ConditionalOnProperty(name = "platform.telemetry.database-enabled", havingValue = "true")
    DatabaseTelemetry databaseTelemetry(JdbcTemplate jdbc, MeterRegistry registry,
            @Value("${platform.telemetry.role}") String role) {
        return new DatabaseTelemetry(jdbc, registry, role);
    }
}
