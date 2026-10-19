# AGENTS.md

## Overview
E-Com Bank is an 8-service microservices platform demonstrating distributed 
transactions via orchestrated sagas and an event-sourced ledger. The primary 
subject is `ledger-service` (Postgres, Kafka, event sourcing). The other 
services are legacy e-commerce CRUD demos (H2) proving Feign/Data-REST.

## Build & test
- `mvn clean install -DskipTests` — builds all 8 modules.
- `mvn test` — runs the ledger-service test suite (JUnit + hand-written doubles).
- `cd ecom-frontend && npm start` — Angular dev server.
- `docker-compose up -d` — starts Kafka, Zookeeper, Kafdrop, Keycloak.

## Conventions
- One commit per task. Conventional commits (e.g., `feat(ledger):`, `fix(security):`).
- ADRs in `docs/ARCHITECTURE.md` or inline comments explaining *why* with numbers.
- No 2PC/XA. Saga compensations must be ordered and audited.
- Event sourcing is append-only. No mutable balance columns anywhere.
- Gateway is the only trust boundary. Strip and re-inject identity headers.
- Feature gates default OFF ("absent, not idle" via `@ConditionalOnProperty`).

## Boundaries
- Never modify `docker-compose.yml` outside a milestone that names it.
- Never change a Spring Boot or Angular version without an ADR.
- Do not add `spring-boot-starter-security` to downstream services.
- Never read: `target/`, `node_modules/`, `dist/`, `.idea/`, `.git/`.

## Do not
- Do not introduce 2PC or distributed transactions.
- Do not use `ddl-auto=update`. Schema is owned by Flyway.
- Do not implement a feature that does not have a spec in `specs/`.
