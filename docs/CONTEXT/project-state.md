# Project State Snapshot

> Read this before planning any new feature. It is the fastest path to the
> current truth; `AGENTS.md` holds the standing rules, this file holds the
> current situation.

## 1. Current Branch & Status

- **Branch:** `feature/workflow-scaffolding`
- **Tip:** this commit — `docs(context): add project state snapshot`. Parent is
  `e99bf7a chore: ignore IDE backups and add CI workflow`.
- **Remote:** in sync with `origin/feature/workflow-scaffolding` (nothing unpushed)
- **Working tree:** clean

**What is done on this branch:** only the workflow scaffolding (`AGENTS.md`,
`docs/CONTEXT/`, `specs/` templates, `docs/MEMORY.md`). No service code has
changed on this branch.

**DLT consumer status: SPEC ONLY — NOT IMPLEMENTED.** This is the single most
important correction to carry forward. `specs/001-dlt-consumer.md` describes the
feature in full, but none of it exists in code:

| Expected by the spec | Actual state |
|---|---|
| `DeadLetterTransaction` entity (`dead_letter_transactions`) | Does not exist |
| `DeadLetterTransactionRepository` | Does not exist |
| `DltKafkaConsumerConfig` / `dltListenerContainerFactory` | Does not exist |
| `@KafkaListener` on `ledger-events.DLT` | Does not exist |
| Consumer group `billing-service-dlt` | Does not exist |

Poison messages are still only parked on `ledger-events.DLT` by
`DeadLetterPublishingRecoverer` (`KafkaConsumerConfig.java:80`) and are **not**
persisted anywhere. Do not assume a `dead_letter_transactions` table is
queryable. Both `ARCHITECTURE.md` and `README.md` still list "DLT topic has no
consumer" as a known gap; that remains accurate until the spec is implemented.

## 2. Architecture Overview

Eight Spring Boot services (`discovery`, `config`, `gateway`, `customer`,
`inventory`, `billing`, `order`, `ledger`) plus the Angular 17 frontend in
`ecom-frontend`, with `ledger-service` as the primary subject: Postgres +
Kafka + event sourcing, driving transfers as ordered, compensated sagas
(`VALIDATE → DEBIT → CREDIT → ARCHIVE`). `gateway-service` is the only trust
boundary — it validates Keycloak JWTs (`TELLER`, `MANAGER`, `AUDITOR`) and
re-injects `X-User-Id` / `X-User-Roles`, while the other seven services are
H2-backed CRUD demos (`customer`/`inventory`/`billing`/`order`) proving
Feign and Spring Data REST.

## 3. The Workflow Rules

Every future prompt follows the spec-driven loop. No exceptions:

1. **Read the spec** — the relevant file in `specs/`, plus `AGENTS.md` and this
   snapshot. If no spec exists for the requested feature, the correct first
   output is a spec, not code (`AGENTS.md`: *"Do not implement a feature that
   does not have a spec in `specs/`"*).
2. **List the files** — enumerate every file to be created or edited, and the
   services impacted, before touching anything.
3. **Implement** — then write code, one conventional commit per task
   (`feat(ledger):`, `fix(security):`, `docs(context):`).

Spec files use `specs/TEMPLATE-FEATURE.md` (business context, services
impacted, expected behavior, acceptance criteria, constraints, out of scope) or
`specs/TEMPLATE-BUG.md`. Feature gates default OFF via
`@ConditionalOnProperty`. No new dependency, and no Spring Boot or Angular
version change, without an ADR. Schema is owned by Flyway in `ledger-service`;
`ddl-auto=update` is banned.

## 4. Recent Milestones

| Milestone | Commit | State |
|---|---|---|
| **Spec-driven workflow scaffolding established** — `AGENTS.md`, `docs/CONTEXT/java-backend.md`, `docs/CONTEXT/angular-frontend.md`, `docs/MEMORY.md`, `specs/TEMPLATE-FEATURE.md`, `specs/TEMPLATE-BUG.md` | `aa67ec0` | Done, on this branch |
| **CI workflow + IDE-backup ignores** | `e99bf7a` | Done, on this branch |
| **DLT consumer spec drafted** — `specs/001-dlt-consumer.md`, a full design for consuming `ledger-events.DLT` so poison messages become inspectable rows | this commit | Spec only; accepted for implementation, code not started |
| **Live event streaming + archived transactions + observability** | merge `ae59999` | Done, inherited from `feature/live-event-stream` |
| **Time-travel ledger queries + projection rebuild consistency check** | merge `ae59999` | Done, inherited from `feature/time-travel-ledger` |

## 5. Active Context

The next phase adds a **Backoffice UI / Admin Panel** — an admin surface aimed
at the `MANAGER` role, which the current frontend does not serve beyond the
transfer wizard's role guard.

Existing frontend routes today: `dashboard`, `customers`, `products`, `orders`,
`bills`, `banking`, `banking/transfer-wizard` (TELLER/MANAGER),
`transactions`, `transactions/sagas`, `transactions/sagas/:id`,
`transactions/journal`, `accounts/:id/statement`, `journal`,
`observability/live`, `observability/archived`. There is **no** user
management, system health, or admin CRUD surface among them.

Candidate scope (to be settled by a spec, not by code): Keycloak user
management via the Keycloak Admin API; a system health / settings page
surfacing Kafka lag and connection-pool status; and write-capable e-commerce
CRUD where the existing pages are read-only.

Per §3, the next milestone begins by writing `specs/002-backoffice.md` — no
Angular components and no gateway routes until that spec exists and is
accepted.
