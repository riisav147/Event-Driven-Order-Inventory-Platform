# Transaction Boundaries

```mermaid
flowchart LR
    R[HTTP request] --> S[Application service]
    S --> T[One database transaction]
    T --> U[(PostgreSQL)]
    T -->|commit| O[HTTP response]
    T -->|rollback on failure| E[Error response]
```

Checkout is the important boundary: the active cart lock, authoritative price read, order insert, order-item snapshot inserts, and cart clear commit together. No external payment or messaging call belongs inside this transaction; those are later phases.
