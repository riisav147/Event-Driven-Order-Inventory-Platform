# ADR 001 Modular Monolith First

## Decision

Start with one Spring Boot deployable organized by business capability.

## Reason

The project needs understandable transaction boundaries and domain invariants before distribution. Module boundaries preserve future extraction seams without paying distributed-systems cost prematurely.
