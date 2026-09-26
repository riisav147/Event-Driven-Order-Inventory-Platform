# Inventory Concurrency Design

Reservations use a PostgreSQL conditional update:

```sql
UPDATE inventory
SET available_quantity = available_quantity - :quantity,
    reserved_quantity = reserved_quantity + :quantity
WHERE product_id = :productId AND available_quantity >= :quantity;
```

The affected-row count is the correctness decision. The reservation row and stock update share one transaction. This avoids the read-check-write race and does not depend on a Java monitor or distributed lock. Active reservations are idempotent by `(order_id, product_id)`. Confirming or releasing a non-active reservation returns its existing terminal state and does not apply stock changes twice.
