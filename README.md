# E-Com Bank

> **Moving money across microservices without distributed transactions — and proving every movement to an auditor.**

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen)](https://spring.io/projects/spring-boot)
[![Angular](https://img.shields.io/badge/Angular-19-red)](https://angular.dev/)
[![Kafka](https://img.shields.io/badge/Kafka-7.6-black)](https://kafka.apache.org/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue)](https://www.postgresql.org/)
[![Keycloak](https://img.shields.io/badge/Keycloak-26-blueviolet)](https://www.keycloak.org/)

<!-- HERO SCREENSHOT — replace with latest 16-KPI dashboard -->
![Command Center](docs/screenshots/dashboard/dashboard-light.png)

---

## 1. Context

In banking, a money movement is only correct if it satisfies three constraints simultaneously: **the ledger must balance**, **the audit trail must be complete**, and **no partial transfer may ever leave the system in an inconsistent state**. Traditional monolithic systems solve this with database transactions — a single `BEGIN...COMMIT` guarantees atomicity.

Modern banking platforms no longer run as monoliths. They split into independent services — customer management, ledger, notifications, billing — each with its own database, its own deployment, and its own failure modes. The moment money movement spans two services, **distributed transactions become the central engineering problem.**

This project is a deliberate exploration of that problem at portfolio scale: an 8-service banking platform where transfers are atomic in effect, auditable by construction, and enforced under role-based access control.

---

## 2. Problem Statement

### 2.1 The Core Problem

**How do you move money across microservices without two-phase commit, and prove to an auditor that every movement is correct?**

Three sub-problems fall out of this:

| # | Sub-problem | Why it's hard |
|---|---|---|
| **P1** | No atomic transaction across services | 2PC holds locks across the network — a slow Kafka broker blocks every transfer |
| **P2** | No single source of truth for balance | If two services both store a balance, they will eventually disagree |
| **P3** | No reliable audit trail | "Where did this $500 come from?" must be answerable from the data model, not from logs |

### 2.2 Why Traditional Solutions Fail

| Approach | Why it doesn't work here |
|---|---|
| **Two-phase commit (2PC)** | The transfer spans an event store, a journal table, and a Kafka publisher. No single transaction manager covers all three. 2PC across a broker call blocks every concurrent transfer. |
| **CRUD with a balance column** | A mutable balance has no history. If it drifts from the events that produced it — via a bug, a race, a manual edit — there is no way to detect or repair the drift. |
| **Application-level logging** | Logs are append-only but not queryable. An auditor asking "reconstruct the state on March 1" cannot run that against stdout. |
| **Frontend-only role checks** | A `curl` request bypasses the UI entirely. Authorization that lives only in the browser is not authorization. |

### 2.3 Constraints

- **Money movement must be atomic in effect** — either both debit and credit happen, or neither does.
- **The ledger must balance at all times** — total debits equal total credits, verified on service start and continuously on the dashboard.
- **Every transaction must be auditable** — the state at any past instant must be reconstructible from persisted data alone.
- **RBAC must be enforced at the API** — a valid TELLER token must not be able to bypass the $10,000 limit by calling the endpoint directly.

---

## 3. Solution Overview

The platform applies four architectural patterns that together address P1, P2, and P3.

### 3.1 Saga Orchestration — solves P1

A transfer is a **sequence of local transactions**, each with an explicit compensating action:

_**VALIDATE → DEBIT_SOURCE → CREDIT_DESTINATION → ARCHIVE
↓ (on failure)
COMPENSATE**_


If any step fails, the orchestrator runs compensations in **reverse dependency order** — reverse the credit first, then the debit — because the credit only existed because the debit did. Each reversal is itself recorded as an audit step (`COMPENSATE_CREDIT_*`, `COMPENSATE_DEBIT_*`).

The saga state is persisted to Postgres between steps. A crash mid-transfer resumes from where it left off rather than restarting.

### 3.2 Event Sourcing + CQRS — solves P2

The ledger stores **events, not state**. Every state change is an immutable append:

_**AccountCreated(account=ACC-1DB49304, holder=Alice)
MoneyCredited(account=ACC-1DB49304, amount=+5000.00)
MoneyDebited (account=ACC-1DB49304, amount=-500.00)
**_


Current balance = replay of the stream = $4,500. There is **no balance column anywhere** in the schema. Balances cannot drift from their history because there is no second copy to drift from.

Read models (dashboards, KPIs, account lists) are built as **projections** — SQL aggregates against the same event store. Write side is strongly consistent; read side is eventually consistent.

### 3.3 Double-Entry Bookkeeping — solves P3

Every transaction writes **paired journal entries**: one debit, one credit, equal amounts. An invariant checker (`LedgerIntegrityChecker`) refuses to boot the service if `SUM(debits) ≠ SUM(credits)`.

The dashboard displays a real-time `LEDGER BALANCED` indicator. If it ever turns red, that is a P0 alert.

### 3.4 Three-Layer RBAC — enforces business rules

| Layer | Responsibility | Failure mode it prevents |
|---|---|---|
| **Frontend (Angular)** | `roleGuard` blocks routes; hides buttons | Poor UX — user sees actions they can't take |
| **Gateway (Spring Cloud Gateway)** | Validates JWT; strips client-supplied `X-User-Id`/`X-User-Roles`; injects trusted headers | Token forgery, header spoofing |
| **Backend (ledger-service)** | `TransferLimitPolicy` rejects TELLER transfers > $10,000 at the API | A valid token that bypasses the UI |

---

## 4. Screenshots

### 4.1 Command Center — 16 real-time KPIs - dark mode 

<!-- Replace with latest 16-KPI dashboard screenshot -->
![Command Center](docs/screenshots/dashboard/dashboard-dark.png)

16 KPIs with sparklines, trend arrows, and target thresholds. Polled every 5 seconds without a loading flash.

### 4.2 Range-Aware Analytics — 7D / 30D / 90D / 1Y / ALL

### 4.3 Transfer Wizard — Saga Execution Tracker

<!-- Replace with transfer wizard screenshot -->
![Transfer Wizard](docs/screenshots/transfer-wizard/wizard-step1.png)

Three-step wizard: Select → Review → Execute. The Execute step shows the saga's individual step timeline in real time.

### 4.4 Saga Inspector — Step-by-Step Audit

<!-- Replace with saga inspector screenshot -->
![Saga Inspector](docs/screenshots/sagas/saga-inspector.png)

Every saga is inspectable. Each step carries a name, offset, timestamp, and status. Compensations appear as additional steps.

### 4.5 Journal Explorer — Double-Entry Bookkeeping

![Journal Explorer](docs/screenshots/journal/journal-dark.png)

Every transaction has paired debit/credit entries. The header shows `Total Debits = Total Credits` and a `BALANCED` indicator.

### 4.7 Keycloak — Realm Roles

<!-- Replace with keycloak roles screenshot -->
![Keycloak Roles](docs/screenshots/auth/keycloak-roles.png)

Three roles — TELLER, MANAGER, AUDITOR — declared declaratively in `realm-export.json`.

### 4.8 Authentication — OIDC Login

![Login](docs/screenshots/auth/login.png)

OIDC Authorization Code flow with PKCE. JWT validated at the gateway against Keycloak's JWKS endpoint.

### 4.9 Dark Mode

---

## 5. Architecture

![System Topology](docs/screenshots/architecture/topology.png)

**📖 Full architecture → [ARCHITECTURE.md](./ARCHITECTURE.md)** — 8 Mermaid diagrams, engineering decisions, and honest gap analysis.

Highlights:
- **Discovery-based routing** — no explicit gateway config; Eureka service registry resolves `/{service-id}/**` automatically
- **Event-sourced ledger** — the only service with Postgres; other contexts use in-memory H2
- **Kafka opt-in transport** — `ledger.publisher=http` by default; switch to `kafka` for exactly-once archival
- **Three-layer RBAC** — frontend UX → gateway trust boundary → backend authorization

---

## 6. Key Features

### Backend (Java 21 / Spring Boot 3.3)

- 🎯 **8 microservices** — gateway, discovery, config, customer, inventory, billing, order, ledger
- 📚 **Event Sourcing + CQRS** — append-only event store with projections for reads
- 🔄 **Saga orchestration** — `VALIDATE → DEBIT_SOURCE → CREDIT_DESTINATION → ARCHIVE`, with ordered compensation
- 📒 **Double-entry bookkeeping** — paired journal entries; trial balance verified on startup
- 📨 **Kafka exactly-once semantics** — `acks=all`, `enable.idempotence`, manual ack, DLT for poison messages
- 🔐 **Keycloak RBAC** — 3 roles enforced across frontend, gateway, and backend
- 💰 **$10K teller limit** — enforced at the API, tested with curl

### Frontend (Angular 19 / PrimeNG)

- 📊 **16 real-time KPIs** with sparklines, trend arrows, and target thresholds
- 📈 **Range-aware charts** — 7d / 30d / 90d / 1y / ALL selector recomputes both charts and KPIs
- 🎨 **Design system** — dark/light mode, glassmorphic cards, gradient accents, tabular-nums
- ⚡ **Silent polling** — no loading flash on the 5s refresh cycle; skeletons only on first load

### Analytics (BI)

- 🔬 **Statistical anomaly detection** — 3σ outlier flagging against a rolling 30-day baseline
- 🩺 **Pipeline health KPIs** — projection lag, audit trail completeness, dashboard SLA compliance
- 📉 **SQL aggregate optimization** — 10.8s → 200ms via event-replay elimination

---

## 7. Technical Stack

| Layer | Technologies |
|---|---|
| **Backend** | Java 21 · Spring Boot 3.3 · Spring Cloud Gateway · Spring Data JPA · Flyway |
| **Messaging** | Apache Kafka 7.6 · Zookeeper · Kafdrop |
| **Database** | PostgreSQL 16 (ledger) · H2 in-memory (customer / inventory / billing / order) |
| **Security** | Keycloak 26 · OIDC Authorization Code + PKCE · JWT |
| **Frontend** | Angular 19 · PrimeNG 19 · Chart.js · TypeScript 5.6 |
| **Infrastructure** | Docker Compose · Eureka · Spring Cloud Config |
| **Observability** | Spring Actuator · Micrometer · Custom SLA metrics service |

---

## 8. Engineering Decisions

### Why saga orchestration instead of 2PC?

The transfer path spans an event log, a journal table, and a Kafka publisher. No single transaction manager covers all three, and 2PC would hold locks across a broker call. Saga compensation is expressible in domain terms — *"reverse the credit, then the debit"* — and each reversal is recorded as an audit step.

### Why event sourcing instead of a mutable balance?

A ledger is where "how did we get here" matters as much as "what is true now." Append-only means balances cannot drift from their history. The cost is that queries need projections — accepted because the projection path is fast (SQL aggregates) while the replay path stays the correctness path for single accounts.

### Why Kafka over RabbitMQ?

The read side needs ordered, replayable, partitioned delivery keyed by `transactionId`. Kafka gives offset-based replay, first-class DLT support, and idempotent producers. A queue would discard ordering and replay position.

### Why three layers of RBAC?

The frontend guard is UX. The gateway is the trust boundary — it strips client-supplied identity headers before injecting trusted ones, and fails closed if no principal resolves. The backend enforces business rules that would otherwise be bypassable by curl. Each layer has a different job; none is redundant.

### Why BI metrics on a portfolio project?

Most microservices demos have a dashboard with a handful of KPIs. This one has 16 KPIs including statistical anomaly detection (3σ), projection lag, audit trail completeness, and dashboard SLA compliance. Treating analytics as a first-class system with its own SLIs is the BI discipline the project is designed to demonstrate.

More decisions in [ARCHITECTURE.md §8](./ARCHITECTURE.md#8-engineering-decisions).

---

## 9. Results

### Quantified outcomes

| Metric | Before | After | Change |
|---|---|---|---|
| `/api/accounts` query time | 10.8s | 200ms | **54× faster** |
| KPI trends endpoint (7d) | 1.73s | 679ms | **2.5× faster** |
| Event-store re-decodes per account-list request | ~102,000 | 0 | Eliminated |
| Total backend tests | — | 238 | All passing |
| Total frontend tests | — | 83 | All passing |
| Seed data (24-month history) | — | 500 customers · 1,100 accounts · 40,000 transactions | — |

### Correctness proofs

- **Double-entry invariant**: `SUM(debits) == SUM(credits)` verified on every service start and continuously on the dashboard
- **Cross-path consistency**: `AUM(now) − AUM(1 year ago) = Net Cash Flow(1 year)` — two independent SQL paths agree to the penny
- **Compensation correctness**: killed ledger-service mid-transfer; the ledger still balances after restart
- **RBAC enforcement**: TELLER `curl` with $15,000 gets HTTP 403; MANAGER gets HTTP 200


## 10. Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+
- Node 20+
- Docker Desktop


### 10.1 Start infrastructure

git clone <repo-url>
cd ecom-app-microservices-main
docker-compose up -d
Starts: Kafka (9092), Zookeeper (2181), Kafdrop (9000), Keycloak (8180), Postgres (5433).


### 10.2 Build and start the 8 services
bash
mvn clean install -DskipTests
Start in this order (IntelliJ recommended):

discovery-service (8761)

config-service (9999) — launch from project root

customer-service + inventory-service (8081, 8082)

billing-service + order-service (8083, 8084)

ledger-service (8085)

gateway-service (8888)

Or use the restart helper:

bash
powershell -File scripts/restart-ledger.ps1
10.3 Start the frontend
bash

cd ecom-frontend
npm install
npm start
10.4 Open the app
http://localhost:4200 — sign in with one of the demo credentials below.

### 11. Test Credentials

| Role	  | Username  | Password	| Permissions |
| TELLER	| teller	| teller123	Transfers up to $10,000
| MANAGER	| manager	| manager123	Unlimited transfers, all features
| AUDITOR	| auditor	|auditor123	Read-only, no money movement

Keycloak admin console: http://localhost:8180 (admin / admin)

### 12. Optional: Seed 2 Years of History
Populate the ledger with 500 customers, 1,100 accounts, and 40,000 transactions over 24 months:

bash
java -jar ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=seed \
  --ledger.seed.enabled=true \
  --ledger.seed.mode=portfolio

Runtime: ~7 minutes. The seeder drives the real saga orchestrator, so seeded history has the same shape as live transactions — same events, same journal entries, same saga steps, backdated.

### 13. Project Structure
text

ecom-app-microservices-main/
├── gateway-service/         # Spring Cloud Gateway + JWT validation
├── discovery-service/       # Eureka server
├── config-service/          # Spring Cloud Config
├── customer-service/        # Customer CRUD (H2)
├── inventory-service/       # Product CRUD (H2)
├── billing-service/         # Bills + Kafka consumer (H2)
├── order-service/           # Orders + Feign clients (H2)
├── ledger-service/          # Event store + journal + sagas (Postgres)
├── ecom-frontend/           # Angular 19 + PrimeNG dashboard
├── keycloak/                # Realm export (TELLER / MANAGER / AUDITOR)
├── config-repo/             # Centralized configs
├── scripts/                 # Restart helpers
├── docs/
│   ├── ARCHITECTURE.md      # 8 Mermaid diagrams + decisions
│   ├── DESIGN-BRIEF.md      # UI design system
│   ├── UI-AUDIT.md          # Bug audit and remediation log
│   └── screenshots/         # All project screenshots
├── docker-compose.yml
└── README.md

### 14. Documentation

Document	Purpose

ARCHITECTURE.md	System topology, saga sequence, event sourcing, Kafka topology, auth flow, data model, engineering decisions
docs/DESIGN-BRIEF.md	UI design system — palette, typography, component specs
docs/UI-AUDIT.md	Bug audit and remediation log

Built with: ☕ Java · 🅰️ Angular · 🐘 Postgres · 🔴 Kafka · 🔑 Keycloak
