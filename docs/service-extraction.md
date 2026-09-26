# Phase 11: inventory and notification extraction

## Context and ownership

```text
Customer/admin -> core-platform -> inventory-service -> inventory DB
                       |
                       +-> mock-gateway -> gateway DB (simulation only)
                       |
                     core DB / order outbox -> Kafka -> notification-service
                                                           |
                                                  notification DB
                                                  receipts / dedup / DLT / replay outbox
```

| Owner | Authoritative data | Interface |
| --- | --- | --- |
| Core | Users, catalog, carts, orders, payment intents, saga phases, order outbox | Customer/admin JWT APIs |
| Inventory | Available/reserved quantities, immutable reservation requests and their outcomes | Authenticated internal HTTP v1 |
| Notification | Receipts, processed-event markers, DLT records, replay outbox | Kafka order events; operator HTTP API |
| Mock gateway | Durable simulated charge ledger | Same-key HTTP charge API |

Each application gets only its own database credentials in Compose. Separate PostgreSQL containers enforce physical isolation locally. There are no cross-database joins, foreign keys, or transactions. Product/order/customer UUIDs outside their owner are opaque references. The shared Maven library contains event contracts and outbox/Kafka infrastructure, not repositories or business entities.

Historical core inventory/notification tables remain untouched for audit and rollback planning. They are **not live read models**, must not receive new writes, and must not be used for stock or delivery reporting. Removing that historical data is a separately approved retention task.

## Saga decision and API contract

Orchestration with synchronous, idempotent HTTP commands was chosen: callers usually see an immediate result without introducing another command-topic protocol. A durable core saga supplies asynchronous recovery. The price is temporary unavailability and intermediate states, not a distributed transaction.

Core first commits the payment intent in RESERVING before contacting inventory. Every network call occurs outside core database transactions. Phase changes use a short row lock and an expected-phase check. Concurrent workers may repeat network calls; remote idempotency and phase fencing prevent duplicate effects. No in-memory worker state is required for restart recovery.

Inventory authenticates HTTP Basic user `core` with `INVENTORY_PASSWORD`. Use TLS or mTLS and secret management outside local Compose. Notification uses a distinct operator credential, never the inventory password.

| Command | Body/result | Replay behavior |
| --- | --- | --- |
| PUT /internal/v1/stock/{productId} | `{"quantity":5}`; sets available, preserves reserved | Administrative absolute replacement |
| PUT /internal/v1/reservations/{orderId} | Array of `{"productId":"uuid","quantity":2}`; status RESERVED or REJECTED | Stable order ID plus canonical item list; different list gets 409 |
| POST /internal/v1/reservations/{orderId}/CONFIRMED | Status CONFIRMED | Same terminal command is a no-op |
| POST /internal/v1/reservations/{orderId}/RELEASED | Status RELEASED | Same terminal command is a no-op; opposite terminal command gets 409 |

Reservation is atomic for the entire basket in inventory's database. Product locks use one consistent ordering. Requests must contain 1–1000 distinct positive quantities. A definitive rejection is persisted, so restocking does not silently revive a cancelled order; create a new checkout.

There is no autonomous reservation expiry: releasing stock while a payment is uncertain could oversell a paid order. Resolution is owned by the saga. Inventory publishes no critical events in this design, so it does not need an unused outbox.

## Success sequence

```text
Core DB: intent RESERVING, order CREATED
Core -> Inventory: reserve(orderId, complete basket)
Inventory DB: reserve atomically; repeated command returns same result
Core DB: order RESERVED, saga PAYING
Core -> Gateway: charge("order:" + orderId, immutable amount/currency)
Core DB: payment SUCCESS, order PAID, saga CONFIRMING
Core -> Inventory: CONFIRMED
Core DB transaction: order CONFIRMED + saga DONE + OrderConfirmed outbox
Core outbox -> Kafka -> Notification DB transaction: dedup marker + receipt
```

## Failure and recovery sequence

```text
Reserve succeeds -> gateway DECLINED/ERROR
Core DB: payment outcome + order PAYMENT_FAILED + saga RELEASING
Inventory unavailable -> core records saga_error and next retry; returns HTTP 202
Inventory restarts -> recovery worker repeats RELEASED
Inventory DB: restore available stock once
Core DB: order CANCELLED + saga DONE
```

Lost reservation/confirmation/release acknowledgements repeat the same command. A lost gateway response records UNKNOWN, keeps stock, and reconciles the same provider key. A failure to commit the final order/outbox transaction leaves CONFIRMING and retries without charging again. Outbox publication remains at-least-once; notification's marker and receipt share one local transaction.

HTTP payment responses now include `sagaPhase` and `recoveryReason`. They return 202 until DONE, including SUCCESS/PAID awaiting inventory confirmation. A 200 response can be a definitive cancellation, not necessarily a successful purchase.

The recovery worker runs every five seconds, processes up to twenty due intents per pass, and backs off failed intents from one to sixty seconds. Repeated provider uncertainty or a conflicting inventory terminal state remains an explicit operational condition; it never invents a refund or releases uncertain stock. Inspect:

```sql
SELECT order_id, status, saga_phase, saga_attempts, saga_next_attempt, saga_error
FROM payment_attempts WHERE saga_phase <> 'DONE' ORDER BY saga_next_attempt;
```

Correct the dependency or investigate provider/inventory records before retrying. Customer retries also resume the same intent. Do not manually change financial outcomes or delete dedup markers. A production provider must retain idempotency records for the entire reconciliation period; the local mock retains them indefinitely.

## Existing-data cutover (operator-controlled, not run automatically)

Do not start Phase 11 against an existing live Phase 10 deployment without this maintenance procedure:

1. Back up core, Kafka offsets, and gateway data; stop new checkout/payment/admin-stock/replay writes.
2. With Phase 10 still running, reconcile all PENDING/UNKNOWN payments and all ACTIVE reservations. Require zero reserved quantities. V12/V13 refuse migration otherwise.
3. Drain the old order/replay outbox and consumer/DLT lag. Stop the old core process and its listeners. Do not run old and new notification consumers together.
4. Start and migrate the new inventory and notification databases while ingress remains closed. Export the old inventory's `product_id, available_quantity, reserved_quantity` and import those columns into the new inventory database. Verify row counts and quantity totals; reserved quantities must be zero. Terminal old reservation history remains archived in core; completed payment replays do not recreate it.
5. Copy notification receipts, processed-event markers, and dead letters into their new database. Preserve event IDs, timestamps, offsets, replay metadata and every referenced replay-outbox row. The old outbox must be fully published; copied replay rows must remain PUBLISHED. Restore/set the outbox sequence above the highest imported sequence. Do not reset the existing consumer group offsets or omit dedup markers.
6. Move the mock provider ledger to its own database before retrying old keys. A real provider already owns this data independently.
7. Compare imported counts and stable IDs, then start Phase 11 core, inventory and notification. Smoke-test a new order and an old completed payment replay; open ingress only after validation.

Use reviewed export/import tooling with separate source/target credentials for the one-time migration. No application receives both credentials. No user data was deleted or automatically migrated by this implementation. For a fresh development database, there is nothing to copy.

Rollback after new writes is **not** just starting the old binary: reconcile all in-flight sagas and plan a reverse data migration first. Keep backups and the frozen old tables until that decision is resolved.

## Running and verification

See the root README and the three application READMEs. Compose defines the complete local stack; it is not a production HA/security deployment.

Core never loads notification consumers at runtime. The legacy Kafka regression harness explicitly imports the notification profile for fault-injection tests; the additional isolation test runs a separate notification application and database and proves core has no notification consumer.

Tests cover atomic baskets, stock concurrency, contract conflicts, unknown payments, concurrent/late outcomes, service shutdown during release/confirmation, lost reservation acknowledgement, fresh-orchestrator recovery, and independent notification catch-up/deduplication.

## Guarantees deliberately lost

Order and inventory state are no longer instantly consistent or atomically committed together. A reservation may exist while core still says RESERVING; a charge may have succeeded while the order is PAID awaiting confirmation. Durable intent plus idempotent retry replaces the former local transaction. Two-phase commit would couple availability to every participant and still cannot enlist a real HTTP payment provider. An order page can show its core saga status and, if needed, compose a separate owner API/read model—not join service databases.
