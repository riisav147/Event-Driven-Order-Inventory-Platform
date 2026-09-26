# ADR 004 Flyway Migrations

## Decision

Manage schema changes with versioned Flyway migrations and keep Hibernate at `validate`.

## Reason

Schema history belongs with application history. Explicit migrations are reviewable and reproducible across local, CI, and future deployment environments.
