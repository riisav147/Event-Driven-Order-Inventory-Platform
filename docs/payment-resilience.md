# Phase 10 — payment HTTP resilience

Historical Phase 10 setup below. Phase 11 now defaults to HTTP, gives the mock gateway its own database, and adds durable saga reconciliation. Use the [current run and ownership instructions](service-extraction.md); the HTTP deadlines, circuit and concurrency design below still apply.

## Boundary and business semantics

`PAYMENT_GATEWAY=http` selects the HTTP adapter; `local` remains the default for the earlier synchronous demo. The Resilience4j circuit breaker wraps HTTP attempts only, never local business methods. The separate mock provider process uses the existing durable `mock_gateway_charges` simulation ledger. It is not a real payment integration or a newly extracted business service; database ownership separation remains Phase 11 work.

Every retry uses the **same order-derived idempotency key, amount, currency and body**. The provider checks header/body key agreement and rejects amount/currency changes for existing keys. An explicit SUCCESS completes payment; DECLINED or the mock's explicit known-before-charge ERROR compensates inventory. A timeout, unavailable server, circuit rejection, concurrency rejection, malformed response or HTTP 4xx remains UNKNOWN/RESERVED. HTTP 202 from the payment API means retry/reconcile the same intent, not create a new one. In particular, a 4xx on a reconciliation call cannot prove that an earlier call did not charge.

Retries are bounded inside each API call. There is no automatic order-level reconciliation worker; clients/operators still retry the same payment endpoint. Circuit state is per application instance and resets on restart, while provider charge identity and payment uncertainty remain durable.

## Configuration and rationale

| Setting | Default | Rationale |
| --- | --- | --- |
| `payment.gateway` | `local` | Explicit opt-in to a real network dependency |
| `payment.http.url` | `http://localhost:8081/charges` | Loopback local mock endpoint; no redirects followed |
| `payment.http.connect-timeout` | 200 ms | Bounds connection establishment |
| `payment.http.attempt-timeout` | 750 ms | Request deadline plus bounded wait for the complete response body |
| `payment.http.overall-timeout` | 1700 ms | Shared monotonic budget across attempts and delays; second attempt receives only remaining time |
| Maximum attempts | 2 | One retry at most, not nested retry layers |
| `payment.http.retry-delay` | 50 ms + 0–50 ms jitter | Avoids immediate synchronized retries |
| `payment.http.max-concurrent` | 8 | Semaphore admission, zero queued gateway callers |
| Circuit window / minimum calls | 10 / 5 attempts | Small local demonstration window; not production tuning |
| Circuit failure threshold | 50% | HTTP 5xx/408/429, transport failures and uncertain timeout results are failures |
| `payment.http.open-duration` | 5 seconds | Fail fast before limited recovery probes |
| Half-open probes | 2 | Each logical call makes at most one failed half-open probe; two successes close the circuit |

The ordinary worst-case network wait is `2 × 750 + up to 100 = 1600 ms`, inside the 1700 ms overall budget. The remaining-time check truncates later attempts for tighter configurations. JVM scheduling/GC and the surrounding database work are not a hard real-time SLA. Only remote gateway waits are budgeted here. Cancellation bounds local waiting; it does **not** undo a provider charge or guarantee the remote process stops working.

HTTP 5xx, 408, 429 and connection/timeout failures may be retried with the same key. Ordinary 4xx, malformed replies, business declines and known ERROR results are not retried. HTTP rejections/malformed contracts are ignored by the circuit's infrastructure failure statistics but surfaced as pending diagnostics; they still require investigation. No retry is started when the circuit is already open. An open circuit or full concurrency limit does not manufacture a successful payment or release potentially charged stock.

The concurrency limit is justified by the fault-burst test: it compares forty unrestricted slow HTTP requests with forty simultaneous protected callers. The test verifies immediate admission rejection, bounded network attempts and completion time, rather than claiming higher successful throughput. The mock provider can keep processing requests after client cancellation; the test measures that explicitly. This does not replace admission control for the entire application or tuning the servlet/DB pools.

The adapter uses [JDK HTTP client cancellation and connect timeout](https://docs.oracle.com/en/java/javase/21/docs/api/java.net.http/java/net/http/HttpClient.html) and [Resilience4j circuit state/permissions](https://resilience4j.readme.io/docs/circuitbreaker). Semaphore isolation adds no worker queue or blocking wait.

## Run locally

Start the core application at least once so Flyway creates the existing gateway ledger. The mock process deliberately does not mutate the schema independently.

```bash
docker compose -f docker-compose.dev.yml up -d postgres redis
./mvnw -pl apps/core-platform spring-boot:run
```

Then stop/restart core in HTTP mode and run the gateway separately. To build the standalone gateway classpath:

```bash
./mvnw -B -ntp -pl apps/core-platform -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
# Supply DB_URL, DB_USERNAME and DB_PASSWORD in your shell; DB_PASSWORD is required.
java -cp 'apps/core-platform/target/core-platform-0.1.0-SNAPSHOT.jar:apps/core-platform/target/dependency/*' \
  com.example.gateway.MockGatewayServer
```

In another terminal:

```bash
PAYMENT_GATEWAY=http ./mvnw -pl apps/core-platform spring-boot:run
```

Alternatively, after the database is migrated, the optional Compose profile builds/runs the gateway image:

```bash
docker compose -f docker-compose.dev.yml --profile payment-http up -d --build postgres redis mock-gateway
PAYMENT_GATEWAY=http ./mvnw -pl apps/core-platform spring-boot:run
```

For a future containerized core, use `PAYMENT_GATEWAY_URL=http://mock-gateway:8081/charges`. The standalone mock defaults to loopback binding; the container binds internally to all interfaces but exposes only host loopback. The mock has no authentication and is strictly local-only. `/health` is process liveness, not database readiness. DB credentials are environment-supplied; the Compose password is the existing local-development value, not a deployment secret.

## Reproduce faults

Restart the mock process/container with one of these settings:

| Environment | Simulation |
| --- | --- |
| `MOCK_GATEWAY_OUTCOME=SUCCESS` | Durable successful charge |
| `MOCK_GATEWAY_OUTCOME=DECLINED` | Known business decline |
| `MOCK_GATEWAY_OUTCOME=ERROR` | Known pre-charge failure |
| `MOCK_GATEWAY_OUTCOME=TIMEOUT` | First new-key response uncertain; same-key replay reveals durable success |
| `MOCK_GATEWAY_FAULT=HTTP_500` | HTTP 503 without charging |
| `MOCK_GATEWAY_FAULT=DELAY` | Delay before charging |
| `MOCK_GATEWAY_FAULT=DELAY_AFTER_CHARGE` | Charge commits, response delayed |
| `MOCK_GATEWAY_DELAY_MS=2000` | Delay duration for delay faults |

Existing provider decisions remain stable across restarts/scenario changes. A fault may delay a reply past cancellation while still charging, so the core must retain reservation state until reconciliation. Stop the mock completely to reproduce connection failure. Restore it, wait for the open interval, and retry the original order payment endpoint to exercise recovery.

Example Compose fault switch:

```bash
MOCK_GATEWAY_FAULT=DELAY_AFTER_CHARGE MOCK_GATEWAY_DELAY_MS=2000 \
  docker compose -f docker-compose.dev.yml --profile payment-http up -d --force-recreate mock-gateway
```

## Metrics and diagnosis

Admin-authenticated Actuator metric hooks are exposed at `/actuator/metrics` (customers receive 403):

- `resilience4j.circuitbreaker.state`, calls, failure rate and rejected-call meters, tagged with `name=paymentGateway`.
- `payment.gateway.attempts`, `payment.gateway.retries`.
- `payment.gateway.pending`, with bounded reason values such as CIRCUIT_OPEN, BULKHEAD_FULL and ATTEMPT_TIMEOUT.
- `payment.gateway.results`, tagged by business outcome.
- `payment.gateway.duration` and `payment.gateway.inflight`.

No order IDs, keys, tokens or raw payment bodies are metric labels. Persisted `payment_attempts.failure_reason` records the latest pending reason; `updated_at` tracks reconciliation. Existing order-status history remains the business audit. A full Grafana/Prometheus stack and alerts remain Phase 12, not part of this milestone.

## Verification

```bash
./mvnw -B -ntp -pl apps/core-platform -Dtest=HttpPaymentGatewayTest,HttpPaymentWorkflowIntegrationTest verify
```

The workflow test uses PostgreSQL Testcontainers by default, or an explicitly supplied disposable DB via `-Dphase6.jdbc-url=...`. HTTP fault tests use real loopback sockets, not a mocked HTTP client. They cover transient retry identity, decline/no retry, permanent rejection, malformed success, slow headers/body, shared deadline truncation, connection refusal, circuit open/half-open recovery and failed probes, plus the concurrent burst. The real-database workflow verifies one durable charge after timeout, retained inventory, later confirmation/outbox capture, decline compensation, and metric endpoint authorization.

See the Phase 10 evidence report for measured results and execution limitations. This phase adds no real card/payment provider, no new inventory/notification service extraction, and no production resilience claim.
