# ADR 003 PostgreSQL

## Decision

Use PostgreSQL as the system of record.

## Reason

The roadmap depends on real relational constraints, transactions, MVCC, indexes, locking, and execution plans. PostgreSQL makes those behaviors testable and visible.
