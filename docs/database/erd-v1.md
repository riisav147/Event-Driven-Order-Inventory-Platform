# ERD Version 1

The first schema is relational and migration-driven. Exact column types may evolve through later Flyway migrations.

```mermaid
erDiagram
    users ||--o{ carts : owns
    users ||--o{ orders : places
    products ||--o{ cart_items : appears_in
    carts ||--o{ cart_items : contains
    orders ||--|{ order_items : owns
    products ||--o{ inventory : has
    orders ||--o{ reservations : creates
    products ||--o{ reservations : reserves
    orders ||--o{ payments : attempts

    users { uuid id PK; string email UK; string password_hash; }
    products { uuid id PK; string sku UK; string name; decimal price; boolean enabled; }
    carts { uuid id PK; uuid customer_id FK; string status; }
    cart_items { uuid id PK; uuid cart_id FK; uuid product_id FK; int quantity; }
    orders { uuid id PK; string order_number UK; uuid customer_id FK; string status; decimal total; }
    order_items { uuid id PK; uuid order_id FK; string sku; string name; decimal unit_price; int quantity; }
    inventory { uuid product_id PK,FK; int available_quantity; int reserved_quantity; }
    reservations { uuid id PK; uuid order_id FK; uuid product_id FK; int quantity; string status; }
    payments { uuid id PK; uuid order_id FK; string status; string idempotency_key UK; }
```

Order items copy SKU, name, and price so historical orders remain correct when catalog data changes. Money uses decimal/numeric with an explicit currency; timestamps are stored consistently in UTC. Unique emails, SKUs, order numbers, and relevant idempotency keys are database-enforced.
