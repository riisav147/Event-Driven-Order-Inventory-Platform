# Phase 12: observability and operability

The three services emit compatible telemetry. No log-audit table was added: business history, saga state, outbox and delivery tables already provide durable evidence.

## Local deployment

```bash
docker compose -f docker-compose.dev.yml up --build -d
```

Grafana: http://localhost:3000 (`admin` / `local-grafana-only`), folder **Platform**, dashboard **Order Platform — Operations**.
Prometheus: http://localhost:9090. Tempo trace API: localhost:3200; OTLP HTTP ingestion: localhost:4318.

Compose provisions the dashboard, Prometheus and Tempo data sources, six alert rules and persistent telemetry volumes. Ports bind to loopback. These example credentials, plaintext networking and single-node retention are for local development only. Rotate credentials and add TLS/access controls before external deployment.

Prometheus rules are visible in its Alerts page. **No email, paging or webhook receiver is configured**; connecting an external receiver requires an operational destination and authorization.

## Signals and why they matter

| Signal | Meaning |
| --- | --- |
| HTTP rate, error ratio, p95 | Request demand, failures and tail latency; an average can hide a small group of very slow orders |
| Outbox count / oldest age | Events committed but not delivered; age identifies customer-impacting stalls |
| Saga phase counts / oldest age | Orders waiting on reservation, payment reconciliation or compensation |
| Committed Kafka group lag | Unprocessed order/DLT records, not just the consumer's fetch buffer |
| Unreplayed DLT count | Failures requiring investigation, not an invitation to replay blindly |
| Payment / reservation outcomes | Durable business results, including UNKNOWN and inventory rejection |
| JDBC pool / JVM memory / gateway concurrency | Saturation before error rates rise |
| Cache hit/miss rate | Whether the optional Redis cache is serving useful work |
| Telemetry availability / last success | Distinguishes missing data from a healthy zero backlog |

HTTP, JVM, JDBC pools and Kafka clients use Micrometer's native meters. Redis cache statistics are enabled. See `infra/observability/grafana/dashboards/platform.json` for exact PromQL. Payment and reservation outcome metrics are **gauges of durable row counts**, not request counters; do not apply counter-rate assumptions to them.

Database polling runs every ten seconds against each service's own tables. Queries have two-second statement timeouts and run off the scrape path; failed polls return NaN and availability=0, never fabricated zero backlog. Gauges are approximate snapshots, not accounting reports. Large-table aggregation costs must be measured in the later load-test phase.

Notification polls committed group offsets and log end offsets with bounded Kafka Admin timeouts. It handles missing commits and retained-log start offsets. If the entire notification process is down, its lag series disappears and the service-down alert applies; an independently deployed Kafka exporter would be needed for continuous lag observation during total notification-process downtime.

## Logging schema and privacy

`PLATFORM_LOG_FORMAT=ecs` enables Spring Boot JSON console output in Compose. Unset it for human-readable local logs.

| Field | Source / semantics |
| --- | --- |
| @timestamp, log.level, log.logger, message | ECS logging metadata |
| service.name | Spring application name: core-platform, inventory-service, notification-service |
| traceId, spanId | Micrometer/OpenTelemetry active span (when tracing is active) |
| correlation_id | Valid UUID supplied as X-Correlation-ID, otherwise generated; order operations use the durable order UUID |
| order_id, order_number | Server-resolved order identity when known; order number is recorded by core saga and notification |
| event_id | Durable event UUID on outbox/notification work |
| operation | Fixed operation name: HTTP, order.saga, inventory.reserve/move, payment.gateway, outbox.publish, notification.receive/deadletter |
| outcome | HTTP status or bounded business/operation outcome |
| duration_ms | Elapsed operation duration, not an end-to-end saga duration |

The HTTP logger records the matched route template, not raw URLs or query strings. It never logs headers, JWTs, passwords, charge bodies or complete request/response bodies. Correlation/order header values must be canonical UUIDs and are **not authentication or authorization**. MDC is restored in finally/close to prevent thread-pool leakage.

Order UUIDs, event IDs, order numbers and trace IDs belong in logs/spans only. Do not add them, customer IDs, exception messages or raw URLs as metric labels. Notification's `receipt_staged` log precedes its enclosing transaction commit; the delivery table is the authoritative committed result.

## Following one order

1. Search JSON logs for the supplied `order_number`. The core saga log gives `order_id`, `correlation_id` and `traceId`.
2. Use `order_id` across inventory and notification logs. Outbox logs also supply `event_id`.
3. In Grafana Explore select **Platform Traces** and paste the trace ID. Alternatively search Tempo with `{ span.order_id = "ORDER_UUID" }`.
4. Use [order stuck](runbooks/order-stuck.md) and [Kafka lag](runbooks/kafka-lag.md) for recovery decisions.

REST uses the auto-configured observed RestClient builder. The custom bounded gateway client explicitly injects W3C context. Saga intent and outbox persist traceparent/tracestate, then resume a span after worker restart or broker delay; Kafka observation propagates that span to notification. A replay starts a new operator trace but preserves the original business/event IDs. Historical rows without trace context start new traces and remain correlatable by order/event identity.

The HTTP X-Correlation-ID identifies a particular incoming request; the order UUID is the stable correlation key across retries. Logs bridge these through the same trace and order number. Long-running work can outlive trace retention; use durable saga/outbox records when an old trace is no longer available.

## Health and security

Public liveness includes only process liveness. Readiness includes application readiness and the owned PostgreSQL database. A process can stay alive while its database makes it unready. Health never shows component details, JDBC URLs or credentials, even to an authenticated caller.

Redis is a fallback cache; Kafka outages accumulate a durable outbox; unavailable inventory/provider calls produce recoverable 202 states. They do not participate in readiness, avoiding cascading removal of healthy core processes. Alerts expose these degraded states.

GET /actuator/prometheus requires a distinct Bearer `METRICS_TOKEN`, checked before application authentication. The Compose example is `local-metrics-only` and must match Prometheus provisioning. A customer/admin JWT or notification Basic credential does not authorize a scrape. Core's existing ADMIN-only /actuator/metrics remains unchanged. Environment/configuration endpoints are not exposed.

## Export failures and configuration

OTLP export is asynchronous and optional. Compose enables it with 100% sampling for demonstration; host runs default to export disabled and 10% sampling. The SDK buffers a bounded number of spans and export timeout is one second. The applications do not depend on Prometheus, Grafana or Tempo readiness; a backend outage must lose telemetry rather than block payments.

Environment overrides:

- PLATFORM_LOG_FORMAT=ecs
- METRICS_TOKEN (rotate both application and scrape configuration)
- TRACING_EXPORT_ENABLED=true
- TRACE_SAMPLE_PROBABILITY=0.1 (Compose uses 1.0)
- OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://tempo:4318/v1/traces
- TELEMETRY_DATABASE_ENABLED / TELEMETRY_KAFKA_LAG_ENABLED for diagnostic isolation

Use the same metric names and JSON fields later with CloudWatch/managed exporters; cloud deployment is not part of Phase 12.

## Verification

`./mvnw clean verify` includes trace propagation, backlog/lag change, scrape authorization, health privacy, invalid correlation input, MDC cleanup and database telemetry failure checks.

```bash
cd infra/observability
promtool check rules alerts.yml
promtool test rules alerts.test.yml
```

The alert tests exercise pending periods, firing and recovery using controlled sample sequences. Run `ruby scripts/observability-smoke.rb` from the repository root against a running stack to generate 100 read-only catalog requests and check all dashboard queries, live HTTP/JVM/pool metrics, and the Grafana data source. Override PLATFORM_URL, PROMETHEUS_URL and GRAFANA_URL for non-default ports. See [verification evidence](evidence/phase12-2026-09-26.md) for what was actually executed, including native-vs-Docker limitations.

Implementation references: [Spring Boot tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html), [structured logging](https://docs.spring.io/spring-boot/3.5/reference/features/logging.html), [Tempo monolithic example](https://github.com/grafana/intro-to-mltp/blob/main/tempo/tempo.yaml).
