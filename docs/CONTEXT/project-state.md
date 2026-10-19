# Project State Snapshot

> Read this before planning any new feature. It is the fastest path to the
> current truth; `AGENTS.md` holds the standing rules, this file holds the
> current situation.

## 1. Current Branch & Status

- **Branch:** `feature/workflow-scaffolding`
- **Tip:** this commit — `chore: close backoffice milestone`.
- **Remote:** in sync with `origin/feature/workflow-scaffolding`
- **Working tree:** clean, and the branch is **ready to merge into `main`**
  (11 commits ahead; `main` has not moved since `ae59999`, so it fast-forwards).

**What is on this branch:** the workflow scaffolding (`AGENTS.md`,
`docs/CONTEXT/`, `specs/` templates, `docs/MEMORY.md`) plus two features
delivered through it — the `ledger-events.DLT` consumer in `billing-service`,
and **Backoffice access administration** (gateway facade, Keycloak realm
client, and an Angular page for `MANAGER`).

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
| **Docs realigned with the implementation** — `ARCHITECTURE.md` §5, `README.md` features / known gaps / future work | `87d9344` | Done |
| **Backoffice spec written and approved** — `specs/002-backoffice.md` | `9775265` | Done |
| **Gateway role enforcement** — realm roles → `ROLE_*` authorities; `/backoffice/**` and e-commerce writes MANAGER-only, reads unchanged | `e747f35` | Done |
| **Keycloak admin client** — client-credentials token, cached with single-flight refresh | `c4c4826` | Done |
| **Backoffice API and realm client** — `ecom-backoffice` service account; list / edit roles / enable-disable with lockout guards | `749daf7` | Done |
| **Backoffice UI** — `/backoffice/users`, nav link, proxy entry; Customers and Products hide writes from non-managers | `e596c50` | Done |
| **Milestone closed** — Karma specs for the three failure states, docs and this snapshot updated, merged to `main` | this commit | Done |
| **Live event streaming + archived transactions + observability** | merge `ae59999` | Done, inherited from `feature/live-event-stream` |
| **Time-travel ledger queries + projection rebuild consistency check** | merge `ae59999` | Done, inherited from `feature/time-travel-ledger` |

## 5. Active Context

**The Backoffice is complete** and this branch is ready to merge. `MANAGER` now
has a page of its own at `/backoffice/users`, reachable from the
"Administration → Backoffice" nav item (rendered only for managers) and behind
`roleGuard(['MANAGER'])`.

What changed beyond the page itself:

- **The gateway enforces roles for the first time.** `KeycloakRealmRoleConverter`
  maps `realm_access.roles` to `ROLE_*` authorities; `/backoffice/**` requires
  `ROLE_MANAGER`, and `POST`/`PUT`/`PATCH`/`DELETE` on `/customer-service/**`
  and `/inventory-service/**` are MANAGER-only. Reads stay open to all three
  roles, and the live stream's `?access_token=` WebSocket handshake still
  matches `authenticated()` (verified: 101 with a token, 401 without).
- **Customers and Products hide their write controls** from non-managers, so a
  teller or auditor no longer sees buttons that would answer 403.
- **The gateway holds the Keycloak admin credential.** The `ecom-backoffice`
  confidential client authenticates with `client_credentials`; its service
  account carries `view-users`, `query-users`, `manage-users` and `view-realm`.
  `view-realm` is not optional — `roles/{role}/users`, the call that keeps the
  user list from being an N+1, answers 403 without it.
- The credential is a committed dev-only literal (`backoffice-secret`) in both
  `keycloak/realm-export.json` and the gateway properties. A real deployment
  must override `backoffice.keycloak.client-secret`.

Frontend routes now include `backoffice/users`; every other route is unchanged.

**Open work, in priority order:**

1. The DLT consumer's broker path is still unproven end to end — the unit and
   context suites are green, but no live poison message has been driven through
   `ledger-events.DLT` into `dead_letter_transactions`.
2. Candidate next features, each needing a spec first: a dead-letter operations
   view over `dead_letter_transactions`; a service health / feature-flag
   inventory page; four-eyes approval for high-value transfers.
3. Known limitation, documented rather than fixed: role and enablement changes
   reach a user at their next sign-in — up to 15 minutes (`accessTokenLifespan
   900`) — because no service re-checks Keycloak per request.

Per §3, the next milestone begins by writing a spec in `specs/`; no code until
it exists and is accepted.
