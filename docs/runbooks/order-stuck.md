# Runbook: order stuck before confirmation

Start with the order number, not a new checkout or payment key.

1. Search core JSON logs by order_number. Record order_id, traceId and latest saga outcome; follow the trace in Grafana and correlate inventory/notification by order_id.
2. Read core-owned state:

```sql
SELECT o.order_number, o.status, p.status AS payment_status, p.saga_phase,
       p.saga_attempts, p.saga_next_attempt, p.saga_error, p.gateway_reference
FROM orders o LEFT JOIN payment_attempts p ON p.order_id = o.id
WHERE o.order_number = 'ORDER_NUMBER';
```

3. Compare the dashboard's saga age, inventory HTTP latency, gateway timeout/circuit/concurrency signals, service health and database pool utilization.

| Phase | Investigate | Safe recovery |
| --- | --- | --- |
| RESERVING | Inventory readiness, authentication, availability and immutable request conflict | Restore dependency; worker repeats same order ID |
| PAYING / UNKNOWN | Provider outcome and stable idempotency key; client timeout does not prove no charge | Reconcile same key; retain stock |
| CONFIRMING / PAID | Inventory unavailable or conflicting terminal reservation | Restore dependency; investigate conflicts before intervention; never charge again |
| RELEASING / PAYMENT_FAILED | Release unavailable or terminal conflict | Restore inventory; worker releases once |
| DONE / CONFIRMED, receipt absent | Order succeeded but downstream delivery delayed | Inspect outbox and notification lag/DLT |

4. After repair, the scheduled worker retries due intents. Repeating the same authenticated payment request is also safe. Confirm DONE and the final business state; for paid orders verify one order outbox event and eventual notification receipt.

Do not release UNKNOWN stock, change payment amounts, delete payment intents, force database statuses, create a second payment key, or delete dedup markers to silence an alert. Refunds and conflicting remote outcomes require explicit operator investigation and a separately authorized action.

If traces are missing, check sampling/export/retention and use durable core state plus correlation logs. Missing telemetry is not proof that the dependency succeeded. Inventory and notification evidence must be queried through their own authorized interfaces/operational access, never via a cross-service application join.
