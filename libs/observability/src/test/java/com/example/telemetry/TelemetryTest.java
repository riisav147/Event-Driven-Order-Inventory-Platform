package com.example.telemetry;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.*;
import org.springframework.jdbc.core.JdbcTemplate;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryTest {
    @Test void correlationIsValidatedAndThreadContextDoesNotLeak() throws Exception {
        var filter = new RequestTelemetryFilter("scrape-secret");
        var request = new MockHttpServletRequest("GET", "/api/products");
        var response = new MockHttpServletResponse();
        request.addHeader("X-Correlation-ID", "unsafe\r\npassword");
        MDC.put("prior", "preserve");
        try {
            filter.doFilter(request, response, (req, res) -> {
                assertThat(MDC.get("correlation_id")).doesNotContain("unsafe", "password");
                assertThatCode(() -> UUID.fromString(MDC.get("correlation_id"))).doesNotThrowAnyException();
            });
            assertThat(MDC.get("correlation_id")).isNull();
            assertThat(MDC.get("prior")).isEqualTo("preserve");
        } finally { MDC.clear(); }
    }
    @Test void providedUuidIsReturnedAndScrapeRejectsWrongCredentials() throws Exception {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("GET", "/api/products"); request.addHeader("X-Correlation-ID", id);
        var response = new MockHttpServletResponse();
        new RequestTelemetryFilter("secret").doFilter(request, response, (req, res) -> { });
        assertThat(response.getHeader("X-Correlation-ID")).isEqualTo(id);
        var denied = new MockHttpServletResponse();
        new RequestTelemetryFilter("secret").doFilter(new MockHttpServletRequest("GET", "/actuator/prometheus"), denied,
                (req, res) -> { throw new AssertionError("unauthorized scrape reached endpoint"); });
        assertThat(denied.getStatus()).isEqualTo(401);
    }
    @Test void databaseMetricFailureIsExplicitAndDoesNotEscapeIntoApplicationWork() {
        var registry = new SimpleMeterRegistry();
        var jdbc = mock(JdbcTemplate.class);
        var telemetry = new DatabaseTelemetry(jdbc, registry, "core");
        assertThatCode(telemetry::poll).doesNotThrowAnyException();
        assertThat(registry.get("platform.telemetry.database.available").gauge().value()).isZero();
        assertThat(registry.get("platform.outbox.pending").gauge().value()).isNaN();
    }
    @Test void flowScopeRestoresMdcEvenWhenBusinessOperationThrows() {
        MDC.put("correlation_id", "previous");
        try {
            assertThatThrownBy(() -> {
                try (var scope = new FlowTelemetry().open("test", UUID.randomUUID(), UUID.randomUUID(), null, null)) {
                    throw new IllegalStateException("test");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(MDC.get("correlation_id")).isEqualTo("previous");
            assertThat(MDC.get("order_id")).isNull();
            assertThat(MDC.get("event_id")).isNull();
        } finally { MDC.clear(); }
    }
}
