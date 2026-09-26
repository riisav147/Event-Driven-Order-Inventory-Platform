package com.example.telemetry;

import io.micrometer.tracing.*;
import io.micrometer.tracing.propagation.Propagator;
import org.slf4j.*;
import org.springframework.beans.factory.ObjectProvider;
import java.util.*;

/** Explicit durable-hop tracing. Identifiers are span/log attributes, never metric labels. */
public class FlowTelemetry {
    private final Tracer tracer;
    private final Propagator propagator;
    public FlowTelemetry() { tracer = null; propagator = null; }
    public FlowTelemetry(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(); this.propagator = propagator.getIfAvailable();
    }
    public Map<String, String> headers() {
        Map<String, String> result = new HashMap<>();
        if (tracer != null && propagator != null && tracer.currentSpan() != null)
            propagator.inject(tracer.currentSpan().context(), result, Map::put);
        result.keySet().retainAll(Set.of("traceparent", "tracestate"));
        return result;
    }
    public Scope open(String operation, UUID order, UUID event, String parent, String state) {
        Span span = null;
        Tracer.SpanInScope inScope = null;
        Map<String, String> previous = MDC.getCopyOfContextMap();
        if (tracer != null) {
            if (parent != null && propagator != null) {
                Map<String, String> headers = new HashMap<>();
                headers.put("traceparent", parent);
                if (state != null) headers.put("tracestate", state);
                span = propagator.extract(headers, Map::get).name(operation).start();
            } else span = tracer.nextSpan().name(operation).start();
            inScope = tracer.withSpan(span);
        }
        if (order != null) {
            MDC.put("order_id", order.toString()); MDC.put("correlation_id", order.toString());
            if (span != null) span.tag("order_id", order.toString());
        }
        if (event != null) { MDC.put("event_id", event.toString()); if (span != null) span.tag("event_id", event.toString()); }
        return new Scope(operation, previous, span, inScope);
    }
    public static class Scope implements AutoCloseable {
        private final String operation;
        private final Map<String, String> previous;
        private final Span span;
        private final Tracer.SpanInScope scope;
        private final long started = System.nanoTime();
        private String outcome = "attempted";
        Scope(String operation, Map<String, String> previous, Span span, Tracer.SpanInScope scope) {
            this.operation = operation; this.previous = previous; this.span = span; this.scope = scope;
        }
        public void outcome(String outcome) { this.outcome = outcome; }
        public void close() {
            LoggerFactory.getLogger(FlowTelemetry.class).atInfo().addKeyValue("operation", operation)
                    .addKeyValue("outcome", outcome).addKeyValue("duration_ms", (System.nanoTime() - started) / 1_000_000.0)
                    .log("Order flow step");
            if (span != null) span.tag("outcome", outcome);
            if (scope != null) scope.close();
            if (span != null) span.end();
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}
