# Redis And Idempotency

Product reads use cache-aside through Spring Cache and Redis. Typed JSON serializers support product records and lists without polymorphic deserialization. Entries have a two-minute TTL; nulls and missing products are not cached. Product creation invalidates both caches after commit. Failed Redis reads fall back to PostgreSQL; failed invalidation is logged and TTL bounds stale entries. Connection and command timeouts are 300 ms each. Checkout always reads authoritative prices from PostgreSQL.

`POST /api/orders` requires `Idempotency-Key` (1–200 characters), the bearer token, and the matching `X-Customer-Id`. Use a new random key for each intended checkout and reuse it on network retries. The endpoint has no request body: its SHA-256 fingerprint covers the versioned command and authenticated owner, not mutable cart contents. Replaying a key after changing the cart still returns the original response; a new checkout needs a new key.

The unique `(owner_id, idempotency_key)` claim, order creation, cart clear, and serialized response commit in one PostgreSQL transaction. Concurrent inserts wait for the winning transaction; duplicates read its saved response. If it rolls back, another caller can acquire the key and retry. A request-hash mismatch returns 409. Stored JSON preserves the original response even if the order status later changes. No Redis operation participates in this transaction.

Keys have a 24-hour replay window. An expired key returns 409 and remains reserved; this increment deliberately does not delete keys or allow silent reuse. There is no automatic cleanup job. Long-running database transactions can still make callers wait; no distributed exactly-once guarantee is claimed.

Cache misses can produce concurrent database reads (no cross-instance stampede lock). A reader racing with invalidation can briefly repopulate stale data, bounded by TTL. Catalog staleness is acceptable for browsing; checkout bypasses this cache. The product list remains unpaginated and should be bounded before large catalogs.

## Verification

With Docker running, `./mvnw verify` runs the Testcontainers suites. The Phase 6 suites can also use isolated local services:

```bash
./mvnw -pl apps/core-platform \
  -Dtest=Phase6IntegrationTest,ProductCacheIntegrationTest,ProductCacheFallbackTest \
  -Dphase6.jdbc-url=jdbc:postgresql://127.0.0.1:55432/phase6 \
  -Dphase6.db-user=platform -Dphase6.redis-port=56379 test
```

Use a dedicated empty test database: the tests apply migrations and insert disposable records. Set `phase6.db-password` if needed. PostgreSQL checks cover concurrent replay, rollback/retry, owner isolation, expiry, mismatch, and HTTP header/response semantics. Cache tests use real Redis and mocked JDBC to count database reads; outage tests target an unavailable Redis port.

Do not use Redis distributed locks for inventory. The Phase 5 PostgreSQL atomic update is the correctness mechanism.
