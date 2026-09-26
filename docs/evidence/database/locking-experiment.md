# Locking Experiment

The checkout transaction locks the active cart with `SELECT ... FOR UPDATE`. To reproduce lock behavior, open two PostgreSQL sessions and execute the same cart-lock query for one customer. Keep the first transaction open; the second waits until the first commits or rolls back.

Use `pg_stat_activity` and `pg_locks` while the second session waits. Record the PostgreSQL version, transaction isolation level, lock wait duration, and the exact statements used. The application deliberately keeps the lock scope short and does not call an external service while holding it.
