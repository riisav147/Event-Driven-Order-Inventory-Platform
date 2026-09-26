# Runbook: Kafka consumer lag increasing

1. Check ServiceUnavailable and TelemetryBlind first. NaN/missing lag is not zero; if the notification process is down, restore it before expecting its lag collector to report.
2. Compare committed group lag, rate of new orders, outbox count/oldest age, DLT backlog, notification JVM/DB saturation and listener logs. The primary group is platform.notifications.v1; its DLT group adds .dlt.
3. Distinguish where work stops:

| Signal | Likely boundary |
| --- | --- |
| Outbox age rises, group lag flat | Core producer, broker availability, topic configuration, or fenced/expired claims |
| Group lag rises, outbox drains | Consumer throughput, partition assignment, retries, or notification DB contention |
| DLT count rises | Persistent/invalid events; inspect incidents instead of deleting them |
| One hot order stalls its outbox | Per-aggregate ordering intentionally holds later events behind the earlier failure |

4. Run the read-only `bash scripts/messaging-status.sh` for outbox, saga, DLT and group offsets. Check broker/topic availability and retention (order topic seven days, DLT thirty days). Use event_id/order_id/traceId to follow an affected event.
5. Repair dependency/configuration failures. Let leases, retries and deduplication recover normally. If throughput is genuinely insufficient, measure partition distribution and DB bottlenecks before changing consumer concurrency; extra consumers beyond partition count do not help.
6. Confirm lag trends down, oldest outbox age clears, telemetry remains available and affected events have one committed receipt.

Do not reset offsets, purge topics, change group IDs, replay every DLT, delete markers, or invent new event IDs as a quick fix. Retention nearing expiry requires escalation and a reviewed recovery plan. Use the [DLT runbook](../dlt-runbook.md) for authenticated, audited replay.
