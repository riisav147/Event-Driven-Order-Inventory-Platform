# Event Driven Order Inventory Platform

[![CI](https://github.com/riisav147/Event-Driven-Order-Inventory-Platform/actions/workflows/ci.yml/badge.svg)](https://github.com/riisav147/Event-Driven-Order-Inventory-Platform/actions/workflows/ci.yml)

Commerce backend through Phase 13: core order/payment orchestration, independent inventory and Kafka notification services, cross-service observability, and verified container CI/CD.

## Run locally

Requires Java 21 and Docker Compose. For a **fresh development database**:

```bash
docker compose -f docker-compose.dev.yml up --build -d
```

Core API: http://localhost:8080; Swagger: http://localhost:8080/swagger-ui.html. Inventory: port 8082. Notification/operator API: port 8083. Mock payment provider: port 8081. All application/database host ports are loopback-only; credentials in Compose are development examples.

**Existing Phase 10 data requires the [cutover procedure](docs/service-extraction.md#existing-data-cutover-operator-controlled-not-run-automatically).** The application refuses unresolved legacy payments/reservations rather than abandoning them.

Per-service host-run instructions:

- [Core platform](apps/core-platform/README.md)
- [Inventory service](apps/inventory-service/README.md)
- [Notification service](apps/notification-service/README.md)

## Behavior

Customer registration/login, catalog, cart, checkout and order history remain in core. Customer APIs require a bearer token; cart/checkout customer headers must match its subject. Product and stock administration require ADMIN. Checkout requires an Idempotency-Key and replays the original response.

Payment reserves the complete basket over HTTP, charges the simulated provider with a stable key, then confirms or releases inventory. Durable saga phases recover across service outages. Unknown payments retain stock. HTTP 202 includes the recovery phase until DONE. Notifications are deduplicated database receipts, not email delivery.

Kafka order publication uses a transactional outbox. Notification owns its deduplication, DLT and replay outbox. Dead-letter APIs are now on **notification port 8083**, authenticated by its operator credential—not on core.

## Verify

```bash
./mvnw clean verify
```

Database/cache tests default to Testcontainers and need Docker. Kafka uses an embedded KRaft broker unless the existing Kafka regression container switch is enabled. With already-running isolated local test databases and Redis:

```bash
./mvnw clean verify \
  -Dphase6.jdbc-url=jdbc:postgresql://localhost:55432/phase11_core \
  -Dphase6.redis-port=56379 \
  -Dphase11.inventory-url=jdbc:postgresql://localhost:55432/phase11_inventory \
  -Dphase11.notification-url=jdbc:postgresql://localhost:55432/phase11_notification
```

Those test databases must exist; local test credentials default to user platform, empty password. Override phase6.db-user/db-password and phase11.inventory-user/inventory-password / notification-user/notification-password as needed. Do not use production databases.

## Design and evidence

- [Service ownership, contracts, saga sequences, migration and trade-offs](docs/service-extraction.md)
- [Phase 11 verification evidence](docs/evidence/phase11-2026-09-24.md)
- [Outbox design](docs/adr/006-transactional-outbox.md)
- [Payment gateway resilience](docs/payment-resilience.md) (Phase 10 historical setup; Phase 11 now defaults to HTTP)
- [Redis and checkout idempotency](docs/redis-idempotency.md)

## Observability

Compose provisions Prometheus (9090), Grafana (3000) and Tempo (3200), bound to localhost. Grafana development login: admin / local-grafana-only. The provisioned **Order Platform — Operations** dashboard has 18 panels. Application metrics require a dedicated bearer token; public probes expose status only.

- [Logging, metrics, tracing, alerts and operator runbooks](docs/observability.md)
- [Phase 12 verification evidence](docs/evidence/phase12-2026-09-26.md)

With the stack running, execute `ruby scripts/observability-smoke.rb` for read-only traffic and dashboard/data-source checks.

## Containers and CI/CD (Phase 13)

Local verification passed 88 tests on Java 21, all four image builds, business/dashboard smoke tests and the high/critical fixable-vulnerability scan. [Hosted CI and GHCR publication also passed](https://github.com/riisav147/Event-Driven-Order-Inventory-Platform/actions/runs/36253657254). See [container and CI instructions](docs/container-ci.md) for startup, tagging and rollback, and [verification evidence](docs/evidence/phase13.md) for published image tags and limitations.

This is a local learning platform, not a real payment/email integration or production HA deployment. AWS deployment and load-testing phases are not included.
