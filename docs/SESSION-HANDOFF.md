# SESSION HANDOFF — "E-Com Bank" (ecom-app-microservices)

**Repo:** `C:\Users\oussa\OneDrive\Bureau\ecom-app-microservices-main`
**Branch:** `main` · working tree clean · HEAD `ae59999` = "Merge feature/live-event-stream: live events, archived transactions, observability" (2026-09-27)
**Owner:** Oussama Attouch — portfolio project, README screenshots are part of the deliverable
**Next session's job:** build the `ledger-events.DLT` consumer (see §5)

> Read this file first, then `README.md` and `ARCHITECTURE.md` (both at repo root). Read §4.7 before quoting any number from the README — documented drift exists.

---

## 1. Project Identity & Core Objective

An 8-service banking platform that answers one question:

> **How do you move money across microservices without two-phase commit, and prove to an auditor that every movement is correct?**

Three sub-problems drive every design decision:

| # | Sub-problem | Why it is hard |
|---|---|---|
| **P1** | No atomic transaction spans services | A transfer touches an event store, a journal table and a Kafka publisher — no single transaction manager covers all three, and 2PC holds locks across a broker call |
| **P2** | No single source of truth for balance | Two services storing a balance will eventually disagree |
| **P3** | No reliable audit trail | "Where did this $500 come from?" must be answerable from the data model, not from logs |

**Identity nuance that confuses newcomers:** the repo name and four of its services (`customer`, `inventory`, `order`, `billing`) are a legacy e-commerce CRUD demo running on in-memory H2. The banking capability — and the actual point of the project — lives entirely in **`ledger-service`**. Treat the e-commerce services as demo surfaces that also prove Feign/Data-REST interop; do not look for the ledger there.

---

## 2. Current Tech Stack & Architecture

### 2.1 Stack

| Layer | Technologies |
|---|---|
| **Backend** | Java 21 · Spring Boot 3.3 · Spring Cloud Gateway · Spring Data JPA / Data REST · Flyway · Maven multi-module (`att.ossama:Ecom-App:1.0-SNAPSHOT`, 8 modules) |
| **Messaging** | Apache Kafka (confluent `cp-kafka:7.6.1`) · Zookeeper · Kafdrop (UI :9000) |
| **Database** | PostgreSQL 16 for the ledger (`ledger_db` @ `localhost:5433`, user/pass `ledger`/`ledger`) · H2 in-memory for customer / inventory / billing / order |
| **Security** | Keycloak 26 (realm `ecom-bank`, public client `ecom-frontend`, realm roles `TELLER` / `MANAGER` / `AUDITOR`) · OIDC Authorization Code + PKCE · JWT validated at the gateway |
| **Frontend** | Angular 19 (standalone components) · PrimeNG 19 + `@primeng/themes` Aura · Chart.js · `@stomp/stompjs` · `angular-auth-oidc-client` · TypeScript 5.6 · Karma/Jasmine |
| **Infra** | Docker Compose (zookeeper, kafka, kafdrop, keycloak) · Eureka · Spring Cloud Config (native backend → `config-repo/`) |
| **Observability** | Spring Actuator · Micrometer · STOMP WebSocket · custom `SlaMetricsService` |

### 2.2 Ports and startup order

| # | Component | Port | Notes |
|---|---|---|---|
| 1 | `discovery-service` | 8761 | Must start first |
| 2 | `config-service` | 9999 | Launch from project root |
| 3 | `customer-service` | 8081 | H2 |
| 4 | `inventory-service` | 8082 | H2 |
| 5 | `billing-service` | 8083 | H2 — **owns the Kafka consumer + DLT producer** |
| 6 | `order-service` | 8084 | H2 |
| 7 | `ledger-service` | 8085 | **Needs Postgres** — the core service |
| 8 | `gateway-service` | 8888 | Must start last |
| — | Angular dev server | 4200 | `cd ecom-frontend && npm start` |
| — | Keycloak | 8180 | `admin`/`admin` |
| — | Postgres | 5433 | **Not in docker-compose — see §4.7** |

Infrastructure: `docker-compose up -d` → zookeeper 2181, kafka 9092, kafdrop 9000, keycloak 8180.

### 2.3 Architectural shape

- **Routing is discovery-based, not configured.** `gateway-service/application.properties` sets `spring.cloud.gateway.discovery.locator.lower-case-service-id=true`; there are **no explicit `spring.cloud.gateway.routes`**. `/{service-id}/**` resolves through Eureka.
- **One service is event-sourced, the rest are CRUD.** `ledger-service` owns the append-only `event_store`; the other four business contexts are Spring Data REST surfaces.
- **Kafka is opt-in, HTTP is the default.** `ledger.publisher=http` by default; `kafka` switches to exactly-once archival.
- **Trust boundary is the gateway.** Downstream services carry no `spring-boot-starter-security`; they read identity from gateway-injected headers.

---

## 3. Completed Milestones & Features

All of the following is committed and working on `main`.

**Ledger core (`ledger-service`, 102 main Java files, 3 Flyway migrations)**
- Event sourcing + CQRS: append-only `event_store`, `UNIQUE (aggregate_id, sequence_number)` for optimistic concurrency, **no balance column anywhere**. Reads are SQL-aggregate projections; replay stays the correctness path for single accounts.
- Saga orchestration: `VALIDATE → DEBIT_SOURCE → CREDIT_DESTINATION → ARCHIVE`, statuses `COMPLETED` / `COMPENSATING` / `FAILED`, compensations recorded as `COMPENSATE_CREDIT_*` / `COMPENSATE_DEBIT_*` steps and run in reverse dependency order even when the first reversal fails. Saga state persisted to Postgres so a crash mid-transfer resumes rather than restarts.
- Double-entry bookkeeping with paired journal entries; `LedgerIntegrityChecker` refuses to boot the service if `SUM(debits) != SUM(credits)`.
- Time-travel: `?at=` point-in-time queries on six read endpoints (`AtParam`, `SnapshotController`, `SnapshotService`), plus a frontend timeline scrubber.
- Projection rebuild dual-implementation consistency check: `POST /api/admin/projections/rebuild` compares Java event replay against the Postgres aggregate. On the seeded ledger: 51,042 events, 1,115 accounts, 0 mismatches.
- Live event stream: gated STOMP endpoint `ws://localhost:8085/ws/events` → `/topic/events`, batched every 100 ms, plus `POST /internal/live-events` so other services report their own observations onto the same stream.
- Perf work: `/api/accounts` 10.8 s → 200 ms by eliminating per-account event re-decoding; Postgres JIT disabled per-connection (`SET jit = off`) because wide KPI aggregates tripped `jit_above_cost` (anomaly query 1.36 s → 245 ms).

**Messaging (`ledger.publisher=kafka` path)**
- Idempotent producer (`acks=all`, `enable.idempotence=true`, bounded by `DELIVERY_TIMEOUT_MS=10000` so a broker outage fails the ARCHIVE step fast enough for the saga to compensate).
- `billing-service` consumer: `read_committed`, manual ack *after* the archive write, in-memory dedupe by `transactionId`, `auto-offset-reset=earliest`.
- Poison-message handling: `DefaultErrorHandler(recoverer, FixedBackOff(1000L, 3L))` with `DeadLetterPublishingRecoverer` publishing to `record.topic() + ".DLT"` on the **same partition number**.
- Topics `ledger-events` (3 partitions, key `= transactionId`) and `ledger-events.DLT` rely on broker `AUTO_CREATE_TOPICS_ENABLE=true`; there is **no `NewTopic`/`TopicBuilder` bean in the repo**.

**Security**
- Three-layer RBAC: Angular `roleGuard` (UX) → gateway JWT validation with **strip-then-inject** of `X-User-Id`/`X-User-Roles`, failing closed → backend `TransferLimitPolicy` rejecting TELLER transfers above $10,000 at the API (verified by `curl`: TELLER $15,000 → 403, MANAGER → 200).
- Branded split-screen Angular login → Keycloak hosted page; PKCE (S256), state and nonce verified. Credentials never touch the app.

**Frontend**
- 16-KPI Command Center with sparklines, trend arrows and thresholds; 7D/30D/90D/1Y/ALL range selector recomputing charts and KPIs; silent 5 s polling (no loading flash, skeletons only on first load); dark/light design system in `_tokens.scss`.
- Pages: dashboard, customers, products, orders, bills, banking, transfer wizard, transactions, saga list + saga inspector, journal explorer, statement, live event stream, archived transactions, login.
- `proxy.conf.json` sends the five service prefixes to the **gateway on 8888** with no `pathRewrite`.

**Data, tooling, docs**
- Seeder drives the *real* saga (not SQL backfill): profile `seed` + `ledger.seed.enabled=true`, modes `small` / `portfolio` (500 customers, ~1,100 accounts, ~40,000 transactions over 24 months, ~7 min, `publish=false` so backdated history is not re-announced).
- Scripts: `restart-ledger.ps1`, `kill-port.ps1`, `live-stream-probe.mjs` (dependency-free STOMP), `crosscheck-archived-transactions.mjs`, `verify-archived-transactions.mjs` (CDP harnesses).
- Docs: `README.md`, `ARCHITECTURE.md` (8 Mermaid diagrams + decisions + verification appendix), `docs/DESIGN-BRIEF.md`, `docs/UI-AUDIT.md`, `docs/screenshots/`.

---

## 4. Active Technical Decisions & Constraints

### 4.1 Non-negotiable architecture rules
1. **Orchestrated saga with compensating events — never 2PC/XA.** Compensation is expressed in domain terms ("reverse the credit, then the debit") and is itself audited.
2. **Event sourcing for the ledger; no mutable balance column, ever.** If a feature needs a balance, it is a projection or a replay.
3. **Kafka is opt-in, not mandatory.** New transports use `@ConditionalOnProperty` so exactly one bean is alive; ledger startup must not depend on broker availability.
4. **Business rules are enforced at the API, never in the frontend.** A rule that only exists in Angular is not a rule (curl must be blocked).
5. **Gateway is the only trust boundary.** Identity headers are stripped from every request before being re-injected from the validated token, and the flow fails closed. Downstream services stay free of `spring-boot-starter-security`.
6. **Postgres for money, H2 for demo contexts.** Schema is owned by Flyway with `ddl-auto=validate`, never by Hibernate.
7. **Feature gates default OFF and are "absent, not idle".** `@ConditionalOnProperty` means a disabled feature has no handler mapping (it 404s), not a dormant thread. Current gates: `ledger.live-stream.enabled`, `ledger.demo-hooks.enabled`, `ledger.projection-rebuild.enabled`, `ledger.seed.enabled` (+ `seed` profile).
8. **Keycloak is the IdP; the realm is declaratively exported.** Adding a role or user means editing `keycloak/realm-export.json` **and recreating the container** — `docker-compose restart keycloak` silently skips the import (verified on Keycloak 26.0.8). Use `docker-compose up -d --force-recreate keycloak`.

### 4.2 Conventions
- **Packages:** `att.ossama.<service>`; ledger sub-packages by concern — `web`, `saga`, `journal`, `eventstore`, `projection`, `persistence`, `publisher`, `dashboard`, `observability`, `security`, `seed`, `admin`.
- **Git:** work on `feature/*` branches merged into `main`; conventional commits with scope (`feat(observability):`, `fix(ledger):`, `test(sagas):`, `docs(screenshots):`, `chore(scripts):`).
- **Comments explain *why*, with measured numbers** where possible (e.g. "1.36 s → 245 ms with JIT off"). This is a deliberate house style — match it.
- **Frontend:** standalone components, functional guards (`authGuard`, `roleGuard([...])`), interceptor chain, services in `src/app/services`, one folder per feature in `src/app/components`.
- **Testing:** JUnit in `ledger-service/src/test` using hand-written doubles (see `KpiTrendsTestDoubles`) rather than heavy contexts; Karma/Jasmine specs colocated as `*.spec.ts`. Every `*ApplicationTests` is a context-load smoke test.
- **Environment overrides:** `LEDGER_DB_URL`, `LEDGER_DB_USER`, `LEDGER_DB_PASSWORD`.

### 4.3 Known gaps (documented, not hidden)
- Dev proxy bypasses the gateway; services are directly reachable on 8081–8085, so trusted headers are forgeable outside a private network. Production requires private topology.
- `ledger-events.DLT` has **no consumer** — poison messages are parked forever. *This is the next task.*
- WebSocket handshake is unauthenticated by design (see `TODO(security)` in `WebSocketConfig`).
- Kafka→journal dual-write gap (no transactional outbox yet).
- No mTLS between services, no OpenTelemetry tracing, no reconciliation job.

### 4.4 House rule for this next task
The DLT consumer must follow the same discipline as the rest of the repo: gated by default, documented with the reason for each choice, tested with doubles, and reflected in `ARCHITECTURE.md` §5 and README §9.3 so the "NOT FOUND" note is removed rather than left stale.

---

## 5. Next Immediate Steps — build the `ledger-events.DLT` consumer

**Objective:** give `ledger-events.DLT` a real consumer so poison messages are processed and alerted on instead of parked forever, then remove the last "NOT FOUND" from the Kafka topology. This completes the story the observability work just surfaced ("what was published" = live stream; "what was durably received" = archived transactions; "what failed" = *this*).

### 5.1 Exactly where to start

| Concern | File |
|---|---|
| DLT producer + retry policy | `billing-service/src/main/java/att/ossama/billingservice/kafka/KafkaConsumerConfig.java` |
| Main listener | `billing-service/src/main/java/att/ossama/billingservice/kafka/TransactionEventConsumer.java` (`@KafkaListener(topics = "ledger-events")`) |
| Consumer props | `billing-service/src/main/resources/application.properties` (`spring.kafka.consumer.group-id=billing-service`) |
| Topology doc to update | `ARCHITECTURE.md` §5 (table row says "DLT consumer: NOT FOUND") and README §9.3 / §15 |
| Frontend pattern to mirror | `ecom-frontend/src/app/components/observability/archived-transactions/` |

### 5.2 The critical trap — do not reuse the default container factory

`KafkaConsumerConfig.errorHandler` builds its destination as `record.topic() + ".DLT"`. If the new DLT listener is annotated with a plain `@KafkaListener` it picks up `kafkaListenerContainerFactory`, whose `commonErrorHandler` is that same recoverer. A failure inside the DLT listener would then publish to **`ledger-events.DLT.DLT`**, and every subsequent failure would extend the chain — an unbounded DLT ladder.

Required shape:
- A **dedicated container factory** (e.g. `dltListenerContainerFactory`) wired to a **non-recursive** error handler — `DefaultErrorHandler` with no recoverer, or `FixedBackOff` set to STOP — so a failing DLT record is logged and committed, never re-published.
- A **distinct consumer group** (e.g. `billing-service-dlt`), so DLT offsets do not interleave with the `billing-service` archive group and a DLT backlog cannot stall archiving.
- Keep `AckMode` explicit; do not inherit the manual-ack assumption of the main listener unless the handler actually acknowledges.

### 5.3 Decisions to make and record
1. **What "handling" means:** persist to a table + expose a read endpoint, or log at ERROR + a Micrometer counter, or both. Persisting matches the existing `archived_transactions` precedent and would give the frontend a natural second observability page.
2. **Alerting surface:** Micrometer counter exposed via Actuator (`management.endpoints.web.exposure.include=health,info,metrics` in ledger; confirm billing's) so a non-zero DLT count is observable.
3. **Gating:** follow house style — a `@ConditionalOnProperty` flag defaulting to off, absent rather than idle. Confirm with the owner whether this one should default **on** (it is a safety net, not a demo hook).
4. **Topic declaration:** decide whether to add an explicit `NewTopic`/`TopicBuilder` bean for `ledger-events` and `ledger-events.DLT` rather than continuing to rely on `AUTO_CREATE_TOPICS_ENABLE=true`.
5. **Failure semantics:** a DLT record that is unparseable must not be retried forever; choose discard-with-count vs park-in-a-table.

### 5.4 Verification plan
1. Start infra + `billing-service` (and `ledger-service` with `ledger.publisher=kafka`).
2. Publish a malformed record to `ledger-events` (e.g. invalid JSON via `kafka-console-producer`).
3. Confirm three retries at 1 s intervals, then arrival on `ledger-events.DLT` on the **same partition number** as the source record.
4. Confirm the new listener processes it, and that **no `ledger-events.DLT.DLT` topic is created** — check Kafdrop on :9000.
5. Confirm the main archive consumer's lag and throughput are unaffected while the DLT listener is running.
6. Add unit tests using doubles, in the existing style.

### 5.5 Definition of done
- DLT records consumed and surfaced; no recursive DLT possible.
- Tests added and green; `ARCHITECTURE.md` §5 and README gap tables updated (the "DLT consumer: NOT FOUND" note removed).
- A Kafdrop or UI screenshot added under `docs/screenshots/observability/`, matching the evidence convention of the previous milestone.

---

## 6. Environment and state checks before you start

Run these first; they are cheap and catch most wasted-session time.

- `git status` — expect a clean tree on `main`; confirm HEAD is still `ae59999` or a descendant.
- `docker-compose ps` — expect zookeeper, kafka, kafdrop, keycloak up.
- **Postgres is not managed by compose.** `ledger-service` expects `jdbc:postgresql://localhost:5433/ledger_db` (user/pass `ledger`/`ledger`). If the ledger will not start, this is the first thing to check: `Test-NetConnection localhost -Port 5433`.
- `mvn clean install -DskipTests` from the root to build all 8 modules.
- Ports 8081–8085 / 8761 / 8888 / 9999 are frequently held by stale JVMs — use `scripts/kill-port.ps1` rather than assuming a build failure.
- `scripts/restart-ledger.ps1` deliberately does not kill clients connected to 8085.

### 6.1 Documentation drift — do not trust these blindly

| Claim | Reality |
|---|---|
| README §10.2: docker-compose starts **Postgres 5433** | **False.** `docker-compose.yml` contains only zookeeper, kafka, kafdrop and keycloak |
| README claims **238 backend tests / 83 frontend tests** | Tree has 16 backend test files / 116 `@Test`/`@ParameterizedTest`, and 7 spec files / 98 `it()` blocks. Re-verify before citing in the portfolio README |
| README §13 lists `docs/ARCHITECTURE.md` | Actual path is `ARCHITECTURE.md` at repo root; `docs/` holds only `DESIGN-BRIEF.md` and `UI-AUDIT.md` |
| `ecom-frontend/README-DEV-PROXY.md` describes direct 8081–8085 proxying | Committed `proxy.conf.json` routes everything to the gateway on 8888. The config file is the operative behaviour |
| `ARCHITECTURE.md` appendix says no explicit gateway routes | Correct and still true — routing is Eureka discovery-based |

### 6.2 Files that are not part of the build
`split-verify-report.json`, `seed-data.js`, `seed-data.ts`, root `package.json` + `package-lock.json` (faker/axios) and the orphan `src/main/java/att/ossama/Main.java` sit outside the Maven reactor. They appear to be scratch/verification artifacts. Leave them alone unless the owner asks for repo cleanup.
