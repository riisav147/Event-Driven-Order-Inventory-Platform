# Database Evidence

Run these commands against a PostgreSQL environment with representative data and save the outputs here as dated files. Do not replace measured output with invented numbers.

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT id, sku, name, price, currency
FROM products
WHERE enabled = true
ORDER BY name;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id, order_number, status, total, currency
FROM orders
WHERE customer_id = '<customer-id>'
ORDER BY created_at DESC;
```

Compare the plan before and after `V3__add_query_indexes.sql`. Record dataset size, PostgreSQL version, query text, and the observed planning/execution metrics with each result.
