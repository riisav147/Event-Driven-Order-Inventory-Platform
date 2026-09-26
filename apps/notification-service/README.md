# Notification service

Consumes OrderConfirmed v1 and stores simulated notification receipts, not emails. Dedup markers and receipts commit together. Failed events go to Kafka DLT, then the service's own dead-letter table; approved replay is queued in its own outbox.

From the repository root:

```bash
docker compose -f docker-compose.dev.yml up -d notification-db kafka
./mvnw -Dmaven.test.skip=true install
DB_URL=jdbc:postgresql://localhost:5434/notification DB_USERNAME=notification DB_PASSWORD=notification_dev_only NOTIFICATION_PASSWORD=local-notification-only ./mvnw -pl apps/notification-service spring-boot:run
```

Port 8083; GET /actuator/health. Kafka defaults to localhost:9092. The main application enables the notification profile; no core datasource is configured. Flyway reads only `db/notification`.

Operator APIs moved from core to this service:

- GET /api/admin/dead-letters
- GET /api/admin/dead-letters/{id}
- POST /api/admin/dead-letters/{id}/replay with `{"reason":"dependency repaired"}`

Use HTTP Basic user `operator` and `NOTIFICATION_PASSWORD`, not a customer JWT. The password must be supplied. Local credentials/plaintext networking are not production-safe.

Keep `platform.notifications.v1` stable across restarts; changing the group is not a substitute for a reviewed replay. See [cutover and ownership](../../docs/service-extraction.md).
