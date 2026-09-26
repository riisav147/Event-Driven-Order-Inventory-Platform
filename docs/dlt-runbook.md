# Event delivery and DLT runbook (Phase 11 ownership)

Notification owns DLT inspection/replay on port 8083 with its operator credential. Core still owns order publication. See [service extraction](service-extraction.md) for cutover precautions.

## Start and inspect

```bash
docker compose -f docker-compose.dev.yml up --build -d
bash scripts/messaging-status.sh
```

`OUTBOX_RELAY_ENABLED=false` pauses relay scheduling while keeping consumers enabled. `KAFKA_ENABLED=false` disables all broker workers but **continues capturing new order events**. Re-enabling drains pending intents. A CONFIRMED API result includes committed business state and outbox intent, not completed notification delivery.

The inspection script is read-only and reports pending outbox counts/age, expired leases, DLT backlog, and both consumer-group lags. Investigate growing oldest pending age, repeated attempts, any unreviewed DLT incident, or lag approaching retention. Broker recovery normally needs no manual intervention: pending/expired claims are retried. Do not reset every publishing row or create new event IDs.

Do not delete/recreate the broker volume or reuse a deleted topic name against a retained audit database: topic/partition/offset identities assume a continuous Kafka log. A deliberate log reset requires a new topic namespace and a reviewed migration/recovery plan. Tests use a fresh topic namespace per broker run for this reason.

## Failure matrix

| Failure | Durable state | Recovery |
| --- | --- | --- |
| Outbox insertion/serialization failure | Final business transaction rolls back | Retry same payment intent |
| Crash after business commit, before send | PENDING outbox | Relay publishes after restart |
| Kafka unavailable | PENDING or leased row with attempt/error metadata | Backoff capped at 60s, then retry indefinitely |
| Crash after Kafka ack, before DB update | Lease eventually expires | Duplicate delivery; processed marker prevents another receipt |
| Receipt write fails | Receipt and marker both roll back | Three retries; persistent failures go to DLT |
| Malformed/unsupported event | Raw payload and diagnostics routed to DLT | Investigate; no blind payload edits/replay |
| DLT publish fails | Source offset is not recovered successfully | Retry recovery when Kafka is available |
| DLT database recording fails | DLT offset is not committed | Separate consumer retries indefinitely |
| Replay request response lost | Incident points to queued outbox row | Repeat request; same outbox ID |

## Inspect an incident

Use the notification operator credential; do not expose passwords in logs. Core administrator JWTs are not accepted here. Listing returns the newest 50 records; older incident IDs can be located through read-only SQL in notification's database.

```bash
curl --user "operator:$NOTIFICATION_PASSWORD" http://localhost:8083/api/admin/dead-letters
curl --user "operator:$NOTIFICATION_PASSWORD" http://localhost:8083/api/admin/dead-letters/INCIDENT_UUID
```

Inspect `payload`, `message_key`, original topic, failure class/message, and DLT location. Compare the event ID against `processed_events` and its order against business audit tables. Raw payloads may contain sensitive business data; restrict access and avoid copying them into public logs.

## Safe replay

1. Fix the underlying cause (database availability, consumer bug, or supported contract version). For a permanently invalid event, investigate rather than inventing a replacement event ID. This API never changes payloads.
2. Submit the incident ID and a short reason:

```bash
curl -X POST --user "operator:$NOTIFICATION_PASSWORD" -H 'Content-Type: application/json' \
  -d '{"reason":"Consumer fix deployed; reviewed event and business state"}' \
  http://localhost:8083/api/admin/dead-letters/INCIDENT_UUID/replay
```

3. HTTP 202 returns `outboxId`, meaning durably queued, not delivered. Inspect that outbox row until PUBLISHED and verify the processed marker/receipt. If the consumer already processed the event, replay creates no additional receipt. Repeating this same incident request does not queue more copies.
4. If the replay fails again, a new DLT incident is recorded. Diagnose that incident; do not delete markers or repeatedly reset Kafka offsets. A malformed/unsupported envelope returns 409 without queuing.

Useful SQL (read-only, notification database; core has a separate order outbox):

```sql
SELECT id, event_id, status, attempts, last_error, available_at, locked_until
FROM outbox_events WHERE status <> 'PUBLISHED' ORDER BY sequence LIMIT 50;
SELECT * FROM processed_events WHERE event_id = 'EVENT_UUID';
SELECT * FROM notification_receipts WHERE event_id = 'EVENT_UUID';
SELECT id, failure_type, recorded_at, replay_outbox_id, replay_requested_by, replay_reason
FROM dead_letters ORDER BY recorded_at DESC LIMIT 50;
```

## Retention and limitations

Follow [ADR 006](adr/006-transactional-outbox.md): never purge pending work or unresolved incidents; retain deduplication markers indefinitely until an explicit replay horizon is introduced. Broker retention is seven days for orders and 30 days for DLT. PostgreSQL dead-letter records retain payloads beyond broker retention for investigation. No automatic cleanup, paging UI, notifications to operators, or external email delivery is implemented. The command-line checks are operational evidence, not a background monitoring service.

## Verification

```bash
./mvnw -B -ntp -pl apps/core-platform -am \
  -Dtest=KafkaIntegrationTest,OrderMessagingTest -Dsurefire.failIfNoSpecifiedTests=false -Dphase8.testcontainers=true verify
```

Without Docker, omit `-Dphase8.testcontainers=true` to use real embedded KRaft and supply separate disposable PostgreSQL URLs with `-Dphase6.jdbc-url=...` and `-Dphase11.inventory-url=...`. The older property name is retained for compatibility. Tests deliberately inject crashes/failures and print expected retry diagnostics.
