# Project State Snapshot

> Read this before planning any new feature. It is the fastest path to the
> current truth; `AGENTS.md` holds the standing rules, this file holds the
> current situation.

## 1. Current Branch & Status

- **Branch:** `main`
- **Tip:** `0a73ec9` — `feat(frontend): add outbox observability UI`, the tip of the
  transactional-outbox merge; this snapshot is the `docs(context): record
  transactional outbox milestone` commit immediately on top of it.
- **Remote:** in sync with `origin/main` (nothing unpushed)
- **Working tree:** clean

**What is on `main`:** the workflow scaffolding (`AGENTS.md`,
`docs/CONTEXT/`, `specs/` templates, `docs/MEMORY.md`) plus three features
delivered through it — the `ledger-events.DLT` consumer in `billing-service`,
**Backoffice access administration** (gateway facade, Keycloak realm client,
and an Angular page for `MANAGER`), and the **transactional outbox** in
`ledger-service` (V4 schema, `ArchiveStep` seam, relay, admin API, and an
Angular page under Observability). The delivering branches were
`feature/workflow-scaffolding`, `feature/dlt-verification` and
`feature/transactional-outbox`.

**DLT consumer status: IMPLEMENTED AND VERIFIED END-TO-END** (`86d3036`
implementation, `55afb4f` verification marker). Poison messages that exhaust the
main listener's three retries are persisted as inspectable rows instead of being
parked invisibly on a topic:

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

Verified against the local stack, not argued from the code: a malformed record
was published to `ledger-events`; the main listener failed and retried three
times, one second apart; `DeadLetterPublishingRecoverer` republished it to
`ledger-events.DLT`; the DLT consumer persisted it and logged
`DLT_CONSUMER: Persisted poison message to database. …` carrying the original
topic, partition and offset. The broker path is now a demonstrated fact rather
than a design claim.

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
| **Spec-driven workflow scaffolding established** — `AGENTS.md`, `docs/CONTEXT/java-backend.md`, `docs/CONTEXT/angular-frontend.md`, `docs/MEMORY.md`, `specs/TEMPLATE-FEATURE.md`, `specs/TEMPLATE-BUG.md` | `aa67ec0` | Done |
| **CI workflow + IDE-backup ignores** | `e99bf7a` | Done |
| **DLT consumer spec drafted** — `specs/001-dlt-consumer.md`, a full design for consuming `ledger-events.DLT` so poison messages become inspectable rows | `a017ff7` | Done |
| **DLT consumer implemented** — listener on `ledger-events.DLT`, group `billing-service-dlt`, rows written to `dead_letter_transactions` | `86d3036` | Done |
| **Docs realigned with the implementation** — `ARCHITECTURE.md` §5, `README.md` features / known gaps / future work | `87d9344` | Done |
| **Backoffice spec written and approved** — `specs/002-backoffice.md` | `9775265` | Done |
| **Gateway role enforcement** — realm roles → `ROLE_*` authorities; `/backoffice/**` and e-commerce writes MANAGER-only, reads unchanged | `e747f35` | Done |
| **Keycloak admin client** — client-credentials token, cached with single-flight refresh | `c4c4826` | Done |
| **Backoffice API and realm client** — `ecom-backoffice` service account; list / edit roles / enable-disable with lockout guards | `749daf7` | Done |
| **Backoffice UI** — `/backoffice/users`, nav link, proxy entry; Customers and Products hide writes from non-managers | `e596c50` | Done |
| **Milestone closed** — Karma specs for the three failure states, docs and this snapshot updated, merged to `main` | `0cdb273` | Done |
| **DLT consumer verified end-to-end** — a live poison message was retried, republished to `ledger-events.DLT`, consumed and persisted, and the `DLT_CONSUMER:` marker observed in the billing-service console | `55afb4f` | Done |
| **Project finalized** — every spec implemented, verified and merged; zero open items | this commit | Done |
| **Live event streaming + archived transactions + observability** | merge `ae59999` | Done, inherited from `feature/live-event-stream` |
| **Time-travel ledger queries + projection rebuild consistency check** | merge `ae59999` | Done, inherited from `feature/time-travel-ledger` |
| **Transactional Outbox implemented** — V4 schema, ArchiveStep seam, OutboxPublisher, Admin API, and Angular UI. Verified end-to-end (Pending -> Published). | `0a73ec9` | Done |

## 5. Active Context

**Project Status: complete for the delivered features.** Three specs are
implemented, verified and merged to `main`; there are no open items.

| Spec | State | Evidence |
|---|---|---|
| `specs/001-dlt-consumer.md` | Implemented and verified end-to-end | A live poison message was retried three times, republished to `ledger-events.DLT`, consumed and persisted; the `DLT_CONSUMER:` marker appeared in the billing-service console |
| `specs/002-backoffice.md` | Implemented and verified | The API was exercised against the live Keycloak — list, role edit, enable/disable, 403 for non-managers, 409 lockout guards — and the page was checked in the browser by the owner |
| `specs/003-outbox.md` | Implemented, verified end-to-end and merged | The demo ran in the browser: a transfer with Kafka down returned **200** and left the row `PENDING` with `Attempts 0`; after Kafka restarted, **Force Poll** published it and the row read `PUBLISHED` with `Attempts 0`. The relay's abort-on-broker-failure, backoff and in-place parking were exercised against a dead broker, and the API's own 403 plus the gateway's 403 for a teller were both observed |

`MANAGER` has a page of its own at `/backoffice/users`, reachable from the
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

**Documented limitations — properties of the design, not open work:**

- Role and enablement changes reach a user at their next sign-in, up to 15
  minutes (`accessTokenLifespan 900`), because no service re-checks Keycloak per
  request. Session revocation was never in scope.
- DLT rows live in billing-service's H2 in-memory database and die with the JVM,
  like `archived_transactions`; on restart the `billing-service-dlt` group
  re-reads the topic from `earliest`.
- The backoffice's last-manager guard is racy — Keycloak offers no transaction
  across the read and the write — as recorded in the spec.
- The outbox covers the saga's `publishStrict` only. The direct
  `/api/transactions` path still publishes best-effort without writing a row, so
  its dual-write window is unchanged.
- A parked outbox row is never retried and there is no unpark tooling: an operator
  reads it and fixes the cause. The API deliberately does not expose `payload`,
  and the four `TIMESTAMP` columns hold UTC wall clock — hand-written demo SQL
  must use `now() AT TIME ZONE 'UTC'`, or the poller skips the row as future-dated.
- `billing-service` dedupes replays in memory, so a replay after a restart can
  duplicate an archive row; the outbox is at-least-once, as recorded in the spec.

**Next up.** The outbox pattern is complete: publication is now durable end to
end — the saga commits one `outbox_events` row together with its ARCHIVE step, the
relay publishes it with per-row backoff and in-place parking, and the page makes
the queue legible to a MANAGER. The natural next features are the **Dead-Letter
Console** (an operator view over `dead_letter_transactions`, which today is a
database query) and a **Reconciliation service** (checking the event store, the
journal and `billing-service`'s archive against each other on a schedule). Neither
has a spec yet.

Any further feature still starts the same way: a spec in `specs/` first, then
the three-step loop in §3.
