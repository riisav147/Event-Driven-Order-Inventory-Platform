package com.example.telemetry;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.slf4j.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public class RequestTelemetryFilter extends OncePerRequestFilter {
    private final String token;
    public RequestTelemetryFilter(String token) {
        if (token.isBlank()) throw new IllegalArgumentException("Metrics token must not be blank");
        this.token = token;
    }
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // Scrape credentials are distinct from customer JWTs and operator credentials.
        if (request.getRequestURI().equals(request.getContextPath() + "/actuator/prometheus")) {
            String supplied = Objects.toString(request.getHeader("Authorization"), "");
            if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                response.setStatus(401); return;
            }
        }
        Map<String, String> previous = MDC.getCopyOfContextMap();
        String correlation = uuid(request.getHeader("X-Correlation-ID"));
        if (correlation == null) correlation = UUID.randomUUID().toString();
        MDC.put("correlation_id", correlation);
        String order = uuid(request.getHeader("X-Order-ID"));
        if (order != null) MDC.put("order_id", order);
        response.setHeader("X-Correlation-ID", correlation);
        long started = System.nanoTime();
        boolean failed = false;
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException | RuntimeException error) { failed = true; throw error; }
        finally {
            if (!request.getRequestURI().startsWith(request.getContextPath() + "/actuator/")) {
                Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
                LoggerFactory.getLogger(RequestTelemetryFilter.class).atInfo()
                    .addKeyValue("operation", "http").addKeyValue("route", pattern == null ? "unmapped" : pattern.toString())
                    .addKeyValue("outcome", failed ? 500 : response.getStatus())
                    .addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0).log("HTTP request");
            }
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
    private String uuid(String value) {
        if (value == null || value.length() != 36) return null;
        try { return UUID.fromString(value).toString(); } catch (IllegalArgumentException invalid) { return null; }
    }
}
