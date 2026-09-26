# Concurrency Evidence

`InventoryConcurrencyTest` launches 100 competing one-unit reservations against inventory containing 10 units. The expected and asserted result is exactly 10 successful reservations, zero available units, 10 reserved units, and no negative stock.

The test uses PostgreSQL through Testcontainers and should be run with:

```bash
./mvnw -pl apps/core-platform -Dtest=InventoryConcurrencyTest test
```

Repeat the test in CI or locally when Docker is available. The strategy under test is the conditional atomic update in `InventoryService`, not a Java `synchronized` block.
