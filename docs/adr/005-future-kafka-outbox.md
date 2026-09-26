# ADR 005 Future Kafka And Outbox

## Decision

Introduce Kafka only after the synchronous monolith and payment semantics are proven. Use a transactional outbox when database mutations begin producing critical events.

## Reason

A database write followed by a direct publish has a dual-write failure window. The outbox will make the business mutation and event record one transaction, while consumers will remain idempotent under at-least-once delivery.
