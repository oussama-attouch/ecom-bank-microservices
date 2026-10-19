# Project State Snapshot

> Read this before planning any new feature. It is the fastest path to the
> current truth; `AGENTS.md` holds the standing rules, this file holds the
> current situation.

## 1. Current Branch & Status

- **Branch:** `feature/workflow-scaffolding`
- **Tip:** this commit — `docs: update Kafka topology to reflect DLT consumer implementation`.
- **Remote:** in sync with `origin/feature/workflow-scaffolding` (nothing unpushed)
- **Working tree:** clean

**What is on this branch:** the workflow scaffolding (`AGENTS.md`,
`docs/CONTEXT/`, `specs/` templates, `docs/MEMORY.md`) and the first feature
delivered through it — the `ledger-events.DLT` consumer in `billing-service`.

**DLT consumer status: IMPLEMENTED** (`86d3036 feat(kafka): implement
ledger-events.DLT consumer`). Poison messages that exhaust the main listener's
three retries are now persisted as inspectable rows instead of being parked
invisibly on a topic:

| Piece | Location |
|---|---|
| `DeadLetterTransaction` entity — table `dead_letter_transactions` | `billing-service/.../entities/` |
| `DeadLetterTransactionRepository` (`@RepositoryRestResource(exported = false)`) | `billing-service/.../repository/` |
| `DltKafkaConsumerConfig` → `dltListenerContainerFactory` | `billing-service/.../kafka/` |
| `DltTransactionEventConsumer` — `@KafkaListener` on `ledger-events.DLT` | `billing-service/.../kafka/` |
| Consumer group `billing-service-dlt` | `spring.kafka.consumer.dlt-group-id` |

Guarantees that must not be regressed when touching this code:

- The DLT error handler is `new DefaultErrorHandler(new FixedBackOff(0L, 0L))`
  built **without** a recoverer, so `ledger-events.DLT.DLT` cannot be created.
- The `partition` and `offset` columns are **quoted** in the entity
  (`@Column(name = "\"partition\"")`) because both are H2 2.x reserved words;
  unquoting them breaks schema creation at startup.
- Rows are occurrences, not entities: duplicates are expected and intentional.
- The DLT group is deliberately separate from `billing-service` so DLT offsets
  never mix into the archive consumer's commit history.

Behaviour is still unproven end to end: the unit/context suite is green, but no
live poison message has been driven through the listener yet (the spec's
acceptance criteria for the broker path are open).

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
| **DLT consumer spec drafted** — `specs/001-dlt-consumer.md`, a full design for consuming `ledger-events.DLT` so poison messages become inspectable rows | `a017ff7` | Done |
| **DLT consumer implemented** — listener on `ledger-events.DLT`, group `billing-service-dlt`, rows written to `dead_letter_transactions` | `86d3036` | Done, on this branch |
| **Docs realigned with the implementation** — `ARCHITECTURE.md` §5, `README.md` features / known gaps / future work | this commit | Done |
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
