# Inventory service

Owns only inventory quantities and reservation batches. See [contracts and saga guarantees](../../docs/service-extraction.md).

From the repository root, start infrastructure and install reactor dependencies:

```bash
docker compose -f docker-compose.dev.yml up -d inventory-db
./mvnw -Dmaven.test.skip=true install
DB_URL=jdbc:postgresql://localhost:5433/inventory DB_USERNAME=inventory DB_PASSWORD=inventory_dev_only INVENTORY_PASSWORD=local-inventory-only ./mvnw -pl apps/inventory-service spring-boot:run
```

Port 8082; GET /actuator/health. Internal v1 commands require Basic user `core`. The password must be supplied. All listed credentials are local examples, not production secrets.

Flyway reads only `db/inventory`. It needs no core tables or core credentials. Stop/restart is safe: reservation identities and outcomes are durable. No reservation expires independently of the payment saga.
