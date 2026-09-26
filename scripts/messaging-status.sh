#!/usr/bin/env bash
set -euo pipefail
platform_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
compose_file="$platform_root/docker-compose.dev.yml"
docker compose -f "$compose_file" exec -T postgres psql -U platform -d order_platform -c \
  "SELECT status, count(*) AS rows, min(created_at) AS oldest_created, max(attempts) AS max_attempts FROM outbox_events GROUP BY status;"
docker compose -f "$compose_file" exec -T postgres psql -U platform -d order_platform -c \
  "SELECT count(*) AS expired_leases FROM outbox_events WHERE status = 'PUBLISHING' AND locked_until < CURRENT_TIMESTAMP; SELECT saga_phase, count(*), min(saga_next_attempt) FROM payment_attempts WHERE saga_phase <> 'DONE' GROUP BY saga_phase;"
docker compose -f "$compose_file" exec -T notification-db psql -U notification -d notification -c \
  "SELECT count(*) AS unreplayed_incidents, min(recorded_at) AS oldest_incident FROM dead_letters WHERE replay_outbox_id IS NULL; SELECT status, count(*) FROM outbox_events GROUP BY status;"
docker compose -f "$compose_file" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group platform.notifications.v1
docker compose -f "$compose_file" exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server kafka:19092 --describe --group platform.notifications.v1.dlt
