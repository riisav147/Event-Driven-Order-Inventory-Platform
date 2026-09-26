# Core platform

Owns identity, catalog, cart, order, payment orchestration and the order outbox. Inventory is accessed only over HTTP. Notification is a separate Kafka consumer application.

For the full stack, use `docker compose -f docker-compose.dev.yml up --build -d` from the repository root. For core running on the host:

```bash
docker compose -f docker-compose.dev.yml up -d postgres redis kafka inventory-service notification-service mock-gateway
./mvnw -Dmaven.test.skip=true install
KAFKA_ENABLED=true INVENTORY_PASSWORD=local-inventory-only ./mvnw -pl apps/core-platform spring-boot:run
```

Defaults: port 8080; core PostgreSQL localhost:5432; Redis localhost:6379; inventory localhost:8082; HTTP payment gateway localhost:8081. Override DB_URL/DB_USERNAME/DB_PASSWORD, INVENTORY_URL/INVENTORY_PASSWORD, PAYMENT_GATEWAY_URL, KAFKA_BOOTSTRAP_SERVERS as needed. Local credentials are development-only.

GET /actuator/health and /swagger-ui.html remain available. Stock administration remains on core's ADMIN API and forwards to inventory. Dead-letter APIs now belong to notification on port 8083.

Payments return 202 until the durable saga is DONE. A scheduled worker resumes due sagas after restart. See [states, failure handling and migration precautions](../../docs/service-extraction.md).
