# Database Engineering Note

## Transaction boundaries

- Registration: one insert into `users`; a failure rolls back the user creation.
- Product creation: one insert into `products`.
- Cart mutation: find/create the active cart and mutate its item in one transaction.
- Checkout: lock the active cart, read authoritative product prices, insert the order and snapshots, then clear the cart in one transaction. Any failure rolls back the complete checkout.
- Order reads and product reads are read-only operations and do not hold application-managed locks.

## Index rationale

| Index | Query it supports | Trade-off |
|---|---|---|
| `products(enabled, name)` | Enabled catalog listing ordered by product name | Extra write/storage cost on product changes |
| `cart_items(cart_id)` | Active-cart item reads and checkout | Faster joins and cart lookup |
| `orders(customer_id, created_at)` | Customer order history | Supports owner filtering and newest-first ordering |
| `orders(status, created_at)` | Future operational status views | Supports status filtering and newest-first ordering |
| `LOWER(users.email)` | Normalized email uniqueness | Prevents case-variant duplicate accounts |

Indexes are added only when they correspond to a real query. Before/after plans must be collected against a representative PostgreSQL dataset; this repository does not claim performance improvements without those measurements.
