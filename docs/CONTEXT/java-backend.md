# Java Backend Conventions

## Stack
- Java 21, Spring Boot 3.3, Maven multi-module.
- Postgres 16 for the ledger (`ledger_db`). H2 in-memory for others.
- Apache Kafka 7.6 (opt-in via `ledger.publisher=kafka`).
- Keycloak 26 for OIDC / JWT.

## Architecture Rules
- `ledger-service` uses Event Sourcing + CQRS. `event_store` is append-only.
- Saga orchestration: VALIDATE → DEBIT → CREDIT → ARCHIVE. Compensations run in reverse.
- Double-entry bookkeeping: `SUM(debits) == SUM(credits)` enforced at startup.
- Downstream services read identity from `X-User-Id` / `X-User-Roles` injected by the gateway.

## Testing
- Unit tests use hand-written doubles (see `KpiTrendsTestDoubles`), not heavy Spring contexts.
- All 8 services have a `*ApplicationTests` context-load smoke test.
- Run `mvn test` to verify.
