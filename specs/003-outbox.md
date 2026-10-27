# Feature: Transactional Outbox + Observability UI

Verified against the working tree at write time (branch
`feature/transactional-outbox`). Every `path:line` below was read from this
repository, not inferred.

**Revision — Phase 0.5.** The transaction boundary was re-scoped after review
(§3, §4, §5, AC-1/AC-3/AC-9). Every claim those sections make was re-read from the
tree before it was written, and the Delivery plan carries the finding-by-finding
audit trail.

## Business context

**Module:** `ledger-service` (write path, new table, new poller, new admin API),
`gateway-service` (one route rule), `ecom-frontend` (one page). No change to
`billing-service`.

**Role:** bank operations engineer and the architect demoing the pattern.
The read endpoint is `MANAGER`-gated; the polling page is reachable from
Observability → Outbox.

**Problem:** the saga writes to the event store and then publishes to Kafka as
two separate, independently committed operations, so an event can be durably
lost.

The current path, verified at write time:

1. `TransferSagaService.execute(...)` is a plain `@Service` method
   (`TransferSagaService.java:43-44`, overloads at `:75`, `:80`, `:94`) with
   **no `@Transactional` anywhere on the class or its methods**. A grep of every
   `@Transactional` in `ledger-service` returns 42 lines — 41 annotations
   (`JpaEventStore.java:59,65`, `JpaSagaRepository.java:44`,
   `JpaJournalEntryRepository.java:34,40`, and read-only queries) plus one
   javadoc reference at `LedgerSeeder.java:934` — and `TransferSagaService` is
   not one of them.
2. The saga therefore runs as a **sequence of independent committed
   transactions**: the saga row (`:135`, via `JpaSagaRepository.save`
   `:43-45`), each event-store append (`:142`, `:156`, via
   `JpaEventStore.append` `:64-66`), each journal post (`:143`, `:157`), and the
   next `save` in `persist()` (`:256-259`). **That is not a defect, and this
   feature does not change it** (§3.1): the saga writes *after* catching a failed
   step — the `FAILED` step, the compensations, the `COMPENSATING` status — and a
   single saga-wide transaction would roll those writes back.
3. The `ARCHIVE` step (`:177-212`) then calls
   `publisher.publishStrict(new TransactionRecord(txId, "TRANSFER", null,
   source, dest, amount, archiveAt.toString()))` (`:183-186`) — **after** the
   debit and credit are already committed.
4. `publishStrict` is the throwing variant (`KafkaTransactionEventPublisher.java:48-50`,
   contract at `TransactionEventPublisher.java:9-11`), so when Kafka is
   unreadable the saga enters its compensation branch (`:188-212`) and reverses
   a transfer that the ledger has already posted. When the send succeeds and the
   process then dies before `persist()`, the archive is published but the saga
   row never records `COMPLETED`. Both directions are wrong, and neither can be
   repaired afterwards because nothing durable records the intent to publish.
5. `JpaEventStore` already says this out loud about its own live stream: it is
   "a live tail of what the write path did, not a transactional outbox"
   (`JpaEventStore.java:118-126`).

This feature moves publication out of the saga and into a relay that reads
committed rows: the `ARCHIVE` step appends one `outbox_events` row **in the same
transaction as the saga's own archive write** — a transaction that covers that one
step and nothing else (§3) — and a poller publishes it later. The rest of the saga
keeps its per-step transactions. The UI exists to make that state legible — kill
Kafka, run a transfer, watch the row sit there, restart Kafka, watch it publish.

## Services impacted

- **`ledger-service`** — migration `V4`, `OutboxEvent` entity + repository, the
  `ArchiveStep` seam with its two gate-selected implementations (the transactional
  outbox writer, and the unchanged legacy publisher call), `OutboxPublisher`
  (poller), and `OutboxAdminController`. `TransferSagaService` **keeps no
  `@Transactional` of its own** and loses its inline publish only when the outbox
  gate is on (§3). One new property block in `application.properties`.
- **`gateway-service`** — one `pathMatchers` line in `SecurityConfig.java`.
  `/ledger-service/**` already routes (discovery locator,
  `gateway-service/src/main/resources/application.properties:7`), so no route
  definition is added.
- **`ecom-frontend`** — one component trio under
  `components/observability/outbox/`, one route, one nav item, one service
  method. `proxy.conf.json:37-45` **already** proxies `/ledger-service`, so no
  proxy change.
- **Not impacted:** `billing-service` (it consumes `ledger-events` unchanged;
  its in-memory dedupe is the AC-5 mechanism, cited not modified),
  `customer-service`, `inventory-service`, `order-service`,
  `config-service`, `discovery-service`, `keycloak/`.

## Expected behavior

### 1. Feature gates — default OFF

`ledger-service` gates every optional surface with `@ConditionalOnProperty` and
the property absent or `false` in `application.properties`
(`ledger.live-stream.enabled=false:52`, `ledger.demo-hooks.enabled=false:75`,
`ledger.projection-rebuild.enabled=false:84`, `ledger.seed.enabled=false:94`;
`AGENTS.md:21` — *"Feature gates default OFF ('absent, not idle')"*;
`docs/CONTEXT/project-state.md:80-81`).

Two properties gate this feature:

| Property | Default in `application.properties` | Effect when not `true` |
|---|---|---|
| `ledger.outbox.enabled` | `false` | no poller bean, no controller bean, `outbox_events` unread, `/api/admin/outbox` **404**; the saga keeps calling `publishStrict` inline, so publication is unchanged from today |
| `ledger.publisher` | `http` (`:28`) | unchanged existing gate |

`@ConditionalOnProperty(name = "ledger.outbox.enabled", havingValue = "true")` —
**no `matchIfMissing`**, matching `ProjectionAdminController.java:38` and
`DemoHooksController.java:30`. The condition sits on the class, because Spring
evaluates `@Conditional*` at bean-definition time and a condition on a
`@RequestMapping` method would silently do nothing (`ProjectionAdminController.java:17-21`).

The same property also decides **which `ArchiveStep` implementation the saga is
given** (§3.2). The two are exact complements on the key — `havingValue="true"`
for the outbox writer, `havingValue="false"` with `matchIfMissing=true` for the
legacy one — the pattern the two broadcaster beans already use
(`application.properties:44-46`). Two consequences worth stating: with the
property absent or `false` the saga calls `publishStrict` exactly as it does
today, so an untouched checkout behaves identically; and any value other than
`true`/`false` leaves the saga without an `ArchiveStep`, failing the context at
startup instead of silently picking a publication path.

**`ledger.outbox.enabled=true` alone is not sufficient.** `KafkaTemplate` is a
bean of `KafkaProducerConfig`, which is itself gated on `ledger.publisher=kafka`
(`KafkaProducerConfig.java:17`, bean at `:42-45`). **If the gate is on but
`ledger.publisher=http`, the context fails to start because the `KafkaTemplate`
bean is missing** — no custom exception class is used, and none is needed: a
missing required collaborator is already loud and names the bean. That is the
fail-fast this feature needs, because a relay with no producer would answer `200`
while publishing nothing — and, with the outbox path selected, would suppress the
inline `publishStrict` too — which is the exact silent failure this feature
removes. Enable for a demo run with
`--ledger.outbox.enabled=true --ledger.publisher=kafka`.

### 2. Schema — `V4__create_outbox_events.sql`

The existing migrations are `V1__create_event_store.sql`,
`V2__create_journal_and_saga_tables.sql` and `V3__add_seed_indexes.sql`
(`ledger-service/src/main/resources/db/migration/`). **The maximum version in
the tree is `V3`, so `V4` is free** — confirmed, not assumed.

Conventions taken from those files, applied here:

- Primary keys are either `BIGSERIAL` (`V1:2`, `V2:28`) or a UUID **stored as a
  string**: `journal_entries.id VARCHAR(36)` (`V2:2`), with the application
  generating it via `UUID.randomUUID().toString()`
  (`TransferSagaService.java:326-328`). The outbox id is caller-known before
  commit, so it uses the `VARCHAR(36)` form.
- Aggregates are `VARCHAR(255)`: `event_store.aggregate_id` (`V1:3`) and
  `saga_states.transaction_id` (`V2:17`). Both candidate values — the event
  store's account id and the transaction id — are therefore the same type.
- Timestamps are `TIMESTAMP`, **not** `timestamptz` (`V1:7`, `V2:9`, `V2:23-24`,
  `V2:33`), mapped to `Instant` fields (`EventEntity.java:59-60`).
- `spring.jpa.hibernate.ddl-auto=validate` (`application.properties:108`), so
  the entity and this migration must agree exactly; `spring.jpa.open-in-view=false`
  (`:111`), so no lazy loading in the controller.

| Column | Type | Null | Default | Notes |
|---|---|---|---|---|
| `id` | `VARCHAR(36)` | NO | — | PK. App-generated UUID string, per `V2:2`. |
| `aggregate_type` | `VARCHAR(64)` | NO | — | `TRANSACTION` for this feature. |
| `aggregate_id` | `VARCHAR(255)` | NO | — | The **transaction id**, which is also the Kafka key (§5). Mirrors `V2:17`. |
| `event_type` | `VARCHAR(64)` | NO | — | The `TransactionRecord.type()` value (`TRANSFER`), i.e. the same discriminator the published JSON carries. |
| `payload` | `TEXT` | NO | — | The exact bytes to publish. `TEXT` per `V1:6` / `EventEntity.java:56`. |
| `created_at` | `TIMESTAMP` | NO | — | The saga timeline instant `archiveAt`, not the wall clock (§4). |
| `published_at` | `TIMESTAMP` | YES | — | Set once, on broker acknowledgement, from the poller's injected `Clock` (`TimeConfig.java:20-22`; §5). |
| `attempt_count` | `INTEGER` | NO | `0` | Incremented on **failed** attempts only. |
| `last_error` | `TEXT` | YES | — | Truncated failure summary; cleared on success. |
| `parked_at` | `TIMESTAMP` | YES | — | Parking mechanism (§5.5). Non-null ⇒ never retried automatically. Stamped from the injected `Clock`, in the **same `UPDATE`** as the final failed attempt. |
| `next_attempt_at` | `TIMESTAMP` | YES | — | Backoff gate; null ⇒ immediately eligible (and null again once parked). Computed from the injected `Clock`. |

Constraints: `PRIMARY KEY (id)`, `CHECK (attempt_count >= 0)`,
`CHECK (published_at IS NULL OR parked_at IS NULL)` (a row can never be both).

Indexes, with purposes bounded by what §6 actually queries:

| Index | Definition | Purpose |
|---|---|---|
| `idx_outbox_pending` | `(created_at)` **partial** `WHERE published_at IS NULL AND parked_at IS NULL` | the poller's claim query — predicate identical, so the planner can use it |
| `idx_outbox_aggregate_created` | `(aggregate_id, created_at)` | forensic lookup of one transaction's event; **not** required for ordering (§6) |
| `idx_outbox_created_at` | `(created_at DESC)` | the admin list's `ORDER BY created_at DESC` |

### 3. The transaction boundary — the invariant

> **If the archive transaction commits, exactly one `outbox_events` row and one
> `ARCHIVE` step row exist for the emitted event. If it rolls back, neither
> exists and no message was sent. Kafka is never called from the saga at all.**

> **Every other saga write keeps its own separately committed transaction.** A
> later failure never rolls back a debit, a credit or a compensation; it appends a
> reversal, exactly as it does today.

Two sentences, and they deliberately disagree about scope. This section explains
why a saga-wide transaction is wrong in both directions, and what the boundary is
instead.

#### 3.1 Why not `@Transactional` on the saga — two independent blockers

**BLK-1 — the annotation would not run.** The HTTP entry point is the two-argument
overload: `TransferController.java:43` calls `execute(request, simulateFailure)`,
i.e. `TransferSagaService.java:75`. That overload self-invokes
`execute(request, simulateFailure, clock.instant(), true)` at `:76`. Spring's
proxy intercepts only calls that arrive *through* the proxy, so a `@Transactional`
on the four-argument method at `:94` is dead code on every request — the
transaction never starts. Annotating the two-argument overload instead would start
one for HTTP, and then hit BLK-2.

**BLK-2 — the transaction would destroy the saga's failure semantics.** The saga is
built on catch-and-record: `:145-150`, `:160-174` and `:188-212` catch a failed step
and then *write* — the `FAILED` step, the compensation legs, the `COMPENSATING`
status, and the terminal `persist()`. Each of those writes goes through a
collaborator whose own `@Transactional` defaults to `REQUIRED`
(`JpaEventStore.java:59,65`, `JpaJournalEntryRepository.java:34,40`), so inside a
saga-wide transaction a failing append is not a neutral event: when the exception
crosses that inner proxy boundary, Spring marks the shared transaction
**rollback-only**. The catch block then writes compensations into a transaction
already condemned, and the boundary's commit throws
`UnexpectedRollbackException`. Three consequences, all wrong in the same direction:

- the compensation legs (`:224`, `:241`) are rolled back, leaving a ledger with a
  debit and a credit that the saga *reported* as reversed;
- the `COMPENSATING` status at `:171-174` / `:209-211` never reaches `saga_states`,
  so the audit trail `AGENTS.md:18` requires — *"Saga compensations must be ordered
  and audited"* — is precisely what is lost;
- the caller (`TransferController.java:44-47`) gets a 500 for a transfer the saga
  had already handled.

A transaction cannot contain the durable record of its own failure. The saga's
per-step commits are load-bearing, not an oversight.

#### 3.2 What is transactional instead: one step, on its own bean

Scope: **the `ARCHIVE` step's row write plus the `outbox_events` insert.** Nothing
else. They belong together because they are two halves of one decision — "this
transfer is archived, and the world will be told" — and separating them *is* the
defect this feature removes: a committed `ARCHIVE` step with no outbox row is a
transfer that will never be published, and an outbox row with no committed
`ARCHIVE` step is a message about a step the saga says never happened.

The mechanism is a **separate Spring bean**, because that is the only way the proxy
applies (BLK-1) and the only way the boundary is out of reach of the saga's own
call graph:

| Element | Where | Gate |
|---|---|---|
| `ArchiveStep` — the seam the saga calls | `saga/ArchiveStep.java` | always present |
| `OutboxArchiveStep` — `@Transactional`, the outbox writer | `outbox/OutboxArchiveStep.java` | `ledger.outbox.enabled=true` |
| `LegacyArchiveStep` — today's `publishStrict` call, verbatim | `saga/LegacyArchiveStep.java` | `ledger.outbox.enabled=false` or absent |

`TransferSagaService` injects the `ArchiveStep` interface, so the object it holds
is a different bean and the call at `:183` passes through the context's proxy. The
saga itself gets **no `@Transactional`, on any overload** — a constraint, not an
omission (§Constraints).

The seam's contract, stated once because both implementations must honour it:

> `archive(saga, record, archiveAt, publish)` records the `ARCHIVE` step and
> returns the saga the caller should continue with; it publishes or enqueues
> exactly when `publish` is true; and it propagates every failure as an exception,
> never swallowing one.

`LegacyArchiveStep` implements that as `publishStrict` plus an in-place `addStep`
— the code at `:183-187` today, unchanged in behaviour. `OutboxArchiveStep`
implements it as the transactional copy below. The gate selects the
implementation, never the caller, so the saga's flow does not branch and AC-9's
compensation clauses cannot drift between configurations.

`OutboxArchiveStep.archive(...)` is one method, one annotation, and only two
writes:

```java
@Transactional
public SagaState archive(SagaState saga, TransactionRecord record, Instant archiveAt, boolean publish) {
    SagaState archived = saga.copyWithStep("ARCHIVE", archiveAt, null, "EXECUTED");
    sagaRepository.save(archived);                        // the ARCHIVE step row
    if (publish) {
        outboxRepository.save(OutboxEvent.pending(        // the intent to publish
                archived.getTransactionId(), record.type(), serialize(record), archiveAt));
    }
    return archived;
}
```

Four properties of that method are the whole guarantee, and every one is checkable
without a database:

1. **One method, one annotation, no inner call.** Nothing can self-invoke around
   the proxy (compare `TransferSagaService.java:76`).
2. **No `try`/`catch` inside it.** A failure propagates to the annotation, which
   rolls both writes back. The saga's compensation logic stays outside, where it
   can still write (BLK-2).
3. **No Kafka call and no request scope inside it** — the two operations that must
   not be held open by a database transaction, and the two that caused this
   feature (§3.4).
4. **The argument is not mutated.** `copyWithStep` returns a copy
   (`SagaState.java:38-57` gains one method beside the three `addStep` overloads),
   so a rollback cannot leave the in-memory saga claiming an `ARCHIVE` step that
   never committed. Without it the catch branch at `:190` would append
   `ARCHIVE ... FAILED` to a saga already carrying `ARCHIVE ... EXECUTED`, and the
   compensation would then persist a saga with two contradictory archive steps —
   in the very path whose audit trail BLK-2 is about.

The saga's call site becomes:

```java
// :183-186 today builds this record inline as publishStrict's argument;
// it becomes a local because the writer serializes it.
TransactionRecord record = new TransactionRecord(
        txId, "TRANSFER", null, source, dest, amount, archiveAt.toString());
if (simulateFailure) {                         // unchanged: throws before the write
    throw new RuntimeException("Simulated archive failure (demo mode)");
}
saga = archiveStep.archive(saga, record, archiveAt, publish);
streamStep(saga, "ARCHIVE", archiveAt, null, "EXECUTED");
```

`streamStep` is `recordStep` minus the `addStep`: the step already exists in the
returned copy, and the broadcast must not be the thing that decides whether it
does. Because it runs after the bean returns, the stream now reports the `ARCHIVE`
step **after** its transaction committed — today a viewer can see
`ARCHIVE EXECUTED` for a step whose row was never written. In the failure branch
nothing changes: `recordStep(saga, "ARCHIVE", ..., "FAILED")` (`:190`) still adds
and streams, on the caller's unmutated saga.

#### 3.3 The saga's other writes — unchanged, and that is the requirement

| Write | Site | Transaction |
|---|---|---|
| saga row (create) | `:135` | own, via `JpaSagaRepository.save` `:44` |
| `VALIDATE` step | `:136` | own, same save call path |
| `DEBIT_SOURCE`: append + journal post | `:142-143` | own: `JpaEventStore.append` `:59,65`, `JpaJournalEntryRepository` `:34,40` |
| `CREDIT_DESTINATION`: append + post + balance check | `:156-158` | own, same |
| **`ARCHIVE` step + outbox row** | `:183-187` | **one transaction — the new one** |
| compensation legs | `:224`, `:241` | own, one per leg |
| terminal state + `persist()` | `:214-216`, `:256-259` | own, via `JpaSagaRepository.save` `:44` |

None of these has an ambient transaction, so each joins nothing and commits alone,
exactly as it does today; `OutboxArchiveStep` is the only caller in the feature
that supplies one. No saga-level `@Transactional` exists to change their behaviour,
which is why AC-9's regression clause is a real obligation rather than a
formality.

Accepted consequence, stated because it is a real window: the terminal `COMPLETED`
write (`:214-216`) is deliberately **not** in the archive transaction (it happens
after the archive step, and on the failure path it must be writable *after* a
rollback). A crash between the archive commit and that write leaves a non-terminal
saga row whose outbox row exists and will still be published. That is strictly
better than today's equivalent — published with the saga lost, or published and
then reversed — and it is recorded as a gap in Design notes rather than hidden.

#### 3.4 What must never join the transaction

Neither is a database write, and both are already documented as outside any
transaction guarantee:

- **the Kafka send** — gone from the saga entirely when the gate is on; the relay
  owns it (§5). With the gate off, `LegacyArchiveStep` keeps the inline
  `publishStrict` call, in its own step-shaped `try`, with no transaction to hold
  open — which is also why the default configuration's behaviour does not move.
- **the live-stream broadcast** (`recordStep` `:275-292`, `:299-324`) — a bounded
  queue by contract, and streaming after commit is what keeps the stream from
  reporting a step that later rolled back. The existing note on the same
  best-effort trade-off is `JpaEventStore.java:118-126`.

#### 3.5 Proof obligation — and its honest limit

`TransferSagaService` is tested with hand-written doubles and no Spring context
(`TransferSagaServiceFundsCheckTest.java:39-64`), and `ledger-service/pom.xml`
carries Postgres and `spring-boot-starter-test` only (`:55-56`, `:74`) — no H2, no
Testcontainers. There is therefore **no DB-backed test harness in this module**,
so "count the rows after a rollback" cannot be asserted by a test here. Phase 2
owes three tests, two of them behavioural and one structural:

1. **Call-path test (unit, no Spring).** With a throwing `ArchiveStep` double,
   `execute` records `ARCHIVE ... FAILED`, ends `COMPENSATING`, reverses both legs,
   and never touches `publisher`. With a recording double it invokes `archive(...)`
   exactly once and streams the step only after it returns.
2. **`copyWithStep` test (unit).** The receiver is unchanged and the copy carries
   the extra step — the rollback-safety property of §3.2(4), provable with no
   transaction manager.
3. **Structure guard (unit).** `OutboxArchiveStep.archive` carries
   `@Transactional`, is not a method of `TransferSagaService`, and
   `TransferSagaService` declares no `@Transactional` on any method. This is the
   test that fails if someone folds the boundary back into the saga (BLK-1).

The commit/rollback atomicity itself rests on Spring's `@Transactional` contract
plus the manual DB check in §10 step 4 (`saga_steps` gains `ARCHIVE`, exactly one
outbox row exists for the transaction). Automating it needs a Testcontainers
harness, which is a test-infrastructure project rather than a line in this feature:
**out of scope**, recorded in **Out of scope** and Design notes.

### 4. The outbox write — `OutboxArchiveStep`

On the outbox path the publish call at `:183-186` is replaced by the writer bean of
§3.2, which builds the row inside the archive transaction. It retains the existing
gates exactly:

- **Still inside the `publish` flag.** The flag means "this run may publish", and
  an outbox row *is* a future publish; with it `false` the archive step is still
  recorded (in the archive transaction) and no row is written.
- **Still after the `simulateFailure` throw** at `:180-182`, so that hook keeps
  meaning what it means today.
- `aggregate_id = transactionId`, `aggregate_type = "TRANSACTION"`,
  `event_type = record.type()`, `id = UUID.randomUUID().toString()`,
  `created_at = archiveAt` (the saga timeline instant, not `Instant.now()` — a
  backdated saga must sort where it happened), `attempt_count = 0`,
  `published_at`/`parked_at`/`last_error` null, `next_attempt_at` null.
- `payload` = the **verbatim** `objectMapper.writeValueAsString(record)` output,
  serialized by the writer with its own default `ObjectMapper`, created the way
  `KafkaTransactionEventPublisher.java:30` creates one — not the Boot-managed
  bean, so the bytes cannot move under a later `spring.jackson.*` setting or an
  injected mapper's configuration. The value is stored as a string and later sent
  as that string, never re-serialized, so byte identity (AC-2) is a property of
  the storage form rather than of two serializers agreeing.

**Correction (COR-1) — the seeder rationale that stood here was false.** It said
the seeder calls `execute(request, simulateFailure, occurredAt, false)` so that
reconstructing history does not look like fresh activity. **The seeder never calls
the saga.** It writes its own saga rows directly, inside one programmatic
transaction for the whole backfill: `LedgerSeeder.java:645-656` calls
`sagaRepository.save(sagaOf(...))` (`:667-689` builds the shape, reusing
`SagaTimeline`), the run is wrapped by `runInOneTransaction` (`:345`, `:939-954`),
and `LedgerSeeder.java` references neither `TransferSagaService` nor the publisher.
So a seed run produces no outbox rows because it never reaches the code that
writes them — AC-9's seed clause is a property of the seeder's design, not of this
flag. Nothing in the tree passes `publish=false` today (`TransferController.java:43`
and the saga tests call the two-argument overload), so the flag is retained as the
contract for "reconstruct, do not announce" and as the only way a test can drive
the non-publishing archive path. With the gate on, `publish=false` still commits
the `ARCHIVE` step — one half of the pair, deliberately, because there is no
intent to publish to pair it with.

Consequence to accept deliberately: on the outbox path the `ARCHIVE` step can no
longer fail because Kafka is down, so the compensation branch at `:188-212` becomes
reachable via `simulateFailure=true` or a genuine database failure of the archive
transaction. That branch stays — it is the saga's guard for an archive that did
not commit — but it stops being Kafka's detour. On the **legacy** path (gate off)
nothing changes: a Kafka failure still throws out of `publishStrict` and still
compensates, exactly as today.

Scope of this fix: only the saga's `publishStrict`. The best-effort `publish(...)`
used by the direct posting path (`TransactionEventPublisher.java:9-11`,
`KafkaTransactionEventPublisher.java:39-45`) is **not** routed through the outbox
in v1; that window is smaller and untouched (see **Out of scope**).

### 5. The poller — `OutboxPublisher`

Ordering, decided first because the rest depends on it.

**The Kafka message key is the transaction id, not an aggregate id**
(`KafkaTransactionEventPublisher.java:17-19`, `:55`;
`kafkaTemplate.send(TOPIC, record.transactionId(), json)`). Preserving current
behaviour means the outbox row's `aggregate_id` **is** that key, and the poller
sends with the outbox row's `aggregate_id` as the key.

**Per-key ordering is required, and is satisfied by construction.** The
publisher emits exactly one `TransactionRecord` per transfer, so an outbox holds
**at most one row per key**: there is no sequence within a key to reorder. The
requirement to preserve is stated as an invariant, because it is what makes this
simplification legal:

> No two `outbox_events` rows may ever share an `aggregate_id` while either is
> unpublished. If a future event type emits a second record for the same
> transaction, the poller must publish a key's rows strictly in `created_at`
> order before moving on, and this section must grow that logic.

That is also why `idx_outbox_aggregate_created` is *not* load-bearing, and why
no per-aggregate advisory locking or grouping is specified.

Flow of one `poll()`:

1. Single-flight: a `ReentrantLock.tryLock()` guard. A second caller **returns 0
   immediately** rather than waiting or running a concurrent batch, so the
   scheduled run and `POST /poll` never overlap and a poll never queues another.
   `processed: 0` is therefore a legitimate answer to a forced poll.
2. Claim in one transaction, in this exact shape:
   ```
   SELECT ... FROM outbox_events WHERE published_at IS NULL AND parked_at IS NULL AND attempt_count < :maxAttempts ORDER BY created_at ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED
   ```
   `next_attempt_at` is additionally honoured
   (`AND (next_attempt_at IS NULL OR next_attempt_at <= :now)`); without that
   predicate the backoff below would have no effect. `:now` is
   `clock.instant()` — the same instant the bookkeeping below stamps with.
3. For each claimed row in `created_at` order: send `payload` verbatim, keyed by
   `aggregate_id`, to `ledger-events`, then exactly **one** `UPDATE`, by primary
   key, on the connection that already holds that row's claim lock:
   - **success** →
     `UPDATE outbox_events SET published_at = :now, last_error = NULL WHERE id = :id AND published_at IS NULL AND parked_at IS NULL`.
     `attempt_count` is **not** touched, so a clean first-attempt publish ends at
     `0` (AC-2). `next_attempt_at` is left alone: a published row is outside the
     claim predicate for good.
   - **failure** → one statement that increments, backs off **and parks**:
     ```sql
     UPDATE outbox_events
        SET attempt_count   = attempt_count + 1,
            last_error      = :error,
            next_attempt_at = :next,     -- NULL on the attempt that parks the row
            parked_at       = CASE WHEN attempt_count + 1 >= :maxAttempts
                                   THEN :now ELSE parked_at END
      WHERE id = :id AND published_at IS NULL AND parked_at IS NULL
     ```
     `attempt_count + 1 >= :maxAttempts` is evaluated against the **pre-update**
     values — every `SET` expression in an `UPDATE` sees the old row, so the CASE
     is not reading the increment it is performing. `:next = :now + min(2^(n-1) × 1s, 300s)`
     with `n` the attempt being recorded.
4. **Abort the rest of the batch on a broker-level failure**
   (`TimeoutException`, `NotConnectedException`, `max.block.ms` expiry): every
   remaining row would fail identically, and at
   `delivery.timeout.ms=10000` (`KafkaProducerConfig.java:38`) attempting all
   `batch-size` rows would hold the claim transaction for minutes. A
   record-level failure (serialization) does not abort the batch — that is one
   row's problem.
5. **Parking is that same `UPDATE`, never a second one.** The row is parked on the
   attempt whose increment reaches `maxAttempts`; `last_error` is retained,
   `next_attempt_at` is cleared, and `outbox.parked.count` is incremented once, on
   the statement reporting one affected row (`Counter` from `MeterRegistry`,
   available through `spring-boot-starter-actuator` — `ledger-service/pom.xml:36` —
   so no new dependency). Parked rows are never retried and never deleted. **No
   `REQUIRES_NEW`, no separate park sweep, no `UPDATE` from a second connection**:
   Design notes gives the deadlock and the lost-row failure this forbids.

**Every timestamp the poller writes comes from the injected `Clock`**
(`TimeConfig.java:20-22`), in both statements and in the claim predicate:
`published_at = clock.instant()`, `parked_at = clock.instant()`,
`next_attempt_at = clock.instant() + backoff`. Not `now()`/`CURRENT_TIMESTAMP` in
SQL, and not `Instant.now()` in Java: a pinned test clock is the only way to assert
the `1s, 2s, 4s` sequence, and a poller stamping rows from two different clocks
cannot be tested at all. (`created_at` is the exception and deliberately so: it is
the saga's business time, §4.)

After a successful send the poller mirrors the publication onto the live stream,
exactly as the publisher does (`KafkaTransactionEventPublisher.java:78-91` —
`SOURCE_KAFKA_PUBLISHER`, `TYPE_KAFKA_PUBLISHED`, the same metadata). Without it,
Observability → Live would go silent for every transfer once the outbox is on —
the relay would be publishing invisibly, which is the one thing that page exists to
prevent. The relay therefore sends with `KafkaTemplate` directly, as §1 requires
(`ledger.publisher=kafka`); it must **not** call `publishStrict`, which is the
legacy path and would go out over HTTP if `ledger.publisher=http`.

Public shape, required so the scheduler and the admin endpoint share one path:

- `OutboxPublisher.poll()` is public, synchronous, returns the number of events
  published in that invocation, and holds all the logic above.
- the `@Scheduled` method only calls `poll()`:
  `@Scheduled(fixedDelayString = "${ledger.outbox.poll-interval:5000}")` —
  `fixedDelay`, so a slow broker cannot stack runs. `@EnableScheduling` is
  already present (`LedgerServiceApplication.java:10`).
- `poll()` is also what `POST /api/admin/outbox/poll` calls (§7).

A deliberate note on Kafka transactions: the poller publishes with the existing
non-transactional producer (`KafkaProducerConfig.java:24-39` — no
`transactionalIdPrefix`), and must not start one. `billing-service` consumes with
`isolation.level=read_committed` (`KafkaConsumerConfig.java:44`), so
non-transactional records are visible immediately; wrapping the send in a Kafka
transaction would make outbox rows appear "published" on our side while remaining
invisible downstream.

### 6. `GET /api/admin/outbox`

Mapped at `/api/admin/outbox`, the same `/api/admin` root the projection
controller uses (`ProjectionAdminController.java:36`). Through the gateway and
the frontend proxy the browser calls
**`/ledger-service/api/admin/outbox`** (`proxy.conf.json:37-45`); the Angular
service already anchors on `/ledger-service/api`
(`ledger.service.ts:10`).

Response:

| Field | Type | Meaning |
|---|---|---|
| `total` | int | all rows |
| `pending` | int | §Design notes status rule |
| `published` | int | " |
| `failed` | int | " |
| `parked` | int | " |
| `events` | array | newest **50** rows, `created_at DESC`, tie-broken by `id DESC` |

`OutboxEventDto`: `id, aggregateType, aggregateId, eventType, status, attempts,
lastError, createdAt, publishedAt`. **`payload` is explicitly out of scope for this
iteration**: the key is absent from every response — not null, and not opt-in. An
earlier draft specified `?includePayload=true`; it is dropped rather than deferred,
because the value is the full event body — the same bytes that go on the topic — and
putting it in a dashboard response that draws none of it would leak transfer details
into a page that never shows them and grow the payload for data nobody reads. The UI
never requests it, and keeping the API lean is the point. An operator who needs the
bytes reads them in the database, deliberately.

Counts cover the whole table, `events` is the newest 50 — so
`pending + published + failed + parked == total` and
`events.length == min(total, 50)`. The implementation uses **four distinct count
queries plus `count()`** — one per status predicate — rather than the single grouped
`count(*) FILTER (...)` this section first sketched. Each predicate is one branch of
the status rule, so the counters and the rows cannot disagree about what a status
means, and the invariant holds because the four predicates partition the rows. The
UI must take its stat cards from the counters, never from `events`.

No `@Transactional` is needed on the read: five cheap aggregate statements and one
page are not worth a snapshot, and `open-in-view=false`
(`application.properties:111`) means the DTOs must be materialized inside the
service method — no lazy entities escape. The counters and the page are separate
statements, so a row can move between them during a refresh; both halves are
individually true, and nothing here needs a consistent snapshot of a live queue.

### 7. `POST /api/admin/outbox/poll`

Calls `OutboxPublisher.poll()` synchronously in the request thread and returns
`{ "triggeredAt": <ISO-8601>, "processed": <int> }`, where `processed` counts
events **published**, not claimed. `POST` because it does work rather than fetch
a representation — the same argument `ProjectionAdminController.java:56-60`
records for its rebuild endpoint. It shares the relay's single-flight guard with
the scheduled tick (§5.1): a call that lands while a batch is running publishes
nothing and answers `processed: 0`; it does not wait, and it does not queue a
second batch. `triggeredAt` comes from the service's injected `Clock`, so it
correlates with the poller's own log lines rather than with the wall clock.

This endpoint is what makes the demo deterministic; without it AC-7 could only be
tested by waiting out a timer.

### 8. Authorization

**`MANAGER` is required on both endpoints.** This service has **no Spring
Security** by design — `HeaderAuthFilter` is a plain servlet filter and says so:
"ledger-service intentionally does not depend on spring-boot-starter-security"
(`HeaderAuthFilter.java:22-24`), and `AGENTS.md:26` forbids adding it. There is
therefore no `@PreAuthorize`, no `SecurityFilterChain` and no `hasRole(...)` to
attach.

The mechanism that exists, and that this endpoint uses, is:

- `CallerContext` — request-scoped, populated from the gateway-injected
  `X-User-Id`/`X-User-Roles` headers (`HeaderAuthFilter.java:57-58`,
  `CallerContext.java:22-33`), with `hasRole(String)` at `CallerContext.java:31-33`.
- Policy objects that consult it and throw `ForbiddenException`
  (`TransferLimitPolicy.java:49-56`), which `ApiExceptionHandler.java:42-45`
  maps to **403**.

So the controller asserts `callerContext.hasRole("MANAGER")` before doing any
work, on `GET` and `POST` alike.

**The brief asked for `ROLE_ADMIN`; that role does not exist in this realm.**
`keycloak/realm-export.json:8-10` defines exactly three roles — `TELLER`,
`MANAGER`, `AUDITOR` — and `MANAGER` is the role this codebase already treats as
privileged: `/backoffice/**` (`SecurityConfig.java:71`) and e-commerce writes
(`:77-80`), plus the frontend's `roleGuard(['MANAGER'])`
(`app.routes.ts:66`). Mapping the requirement onto `MANAGER` is the only option
that needs no realm change, no new Keycloak role and no client reconfiguration;
introducing a fourth role would put a realm edit inside a feature that otherwise
touches no Keycloak file. Recorded as a decision, flagged in Constraints.

**Gateway rule (Phase 5).** Confirmed gap, not a guess: the gateway's rules are
`/actuator/**, /eureka/**` permitAll (`SecurityConfig.java:66`),
`/backoffice/**` MANAGER (`:71`), and MANAGER for writes on
`/customer-service/**` and `/inventory-service/**` (`:77-80`); everything else
falls to `.anyExchange().authenticated()` (`:81`). There is **no
`/api/admin/**` rule and no `/ledger-service` rule at all**, so any signed-in
caller — teller or auditor — reaches the outbox endpoint at the edge. The
service-level check is what actually refuses them (403); Phase 5 adds
`.pathMatchers("/ledger-service/api/admin/**").hasRole(MANAGER_ROLE)` so the edge
refuses first. Both layers are required: the service check survives a direct
call to port 8085, and the gateway check keeps the request off the service.

CSRF is a non-issue: the gateway disables it (`SecurityConfig.java:64`) and the
downstream services have none.

### 9. UI — Observability → Outbox

**Route.** `{ path: 'observability/outbox', component: OutboxComponent,
canActivate: [roleGuard(['MANAGER'])] }` as a child of the guarded `''` route in
`app.routes.ts:38-67`. The children already inherit `authGuard` from the parent
(`:40`), so only the role guard is added — the same shape as
`backoffice/users` (`:66`). Sibling observability routes
`observability/live` (`:59`) and `observability/archived` (`:63`) are unguarded
reads of ledger data; this one is guarded because its API is.

**Component.** `ecom-frontend/src/app/components/observability/outbox/` —
`outbox.component.ts`, `.html`, `.scss` (+ `.spec.ts`, AC-9). Modelled on the
**Backoffice user-admin page**
(`ecom-frontend/src/app/components/backoffice/user-admin/`,
`user-admin.component.ts:34-52`): standalone component, PrimeNG
(`TableModule`, `TagModule`, `ButtonModule`, `TooltipModule`, `MessageService`),
`EmptyStateComponent` / `TableSkeletonComponent` from `shared/`, and the
`gateError` + `describeFailure(err)` pattern (`user-admin.component.ts:67`,
`:99-110`) that turns 404 and 403 into sentences instead of a blank table.

**API calls.** A method on the existing `LedgerService`
(`ledger.service.ts:10`) — `${base}/admin/outbox` — marked
`silentWhen(true)` (`http-context.tokens.ts:13-16`), exactly as
`rebuildProjections()` does at `ledger.service.ts:132`, so failures render in the
card area instead of as a global toast.

**Layout.** Stat cards Pending (warning), Published (success), Failed (danger),
plus **Parked only when `parked > 0`**; then the table. Columns: Event ID
(truncated to 8 chars, full value in the tooltip), Aggregate ID (same), Event
Type, Status badge, Attempts, Last Error (one truncated line, tooltip; em-dash
when null), Created At, Published At (em-dash when null).

**Status badge** covers all four values distinctly and always renders the
literal word: `PENDING` warning, `PUBLISHED` success, `FAILED` danger,
`PARKED` secondary/dark. Colour is never the only signal.

**Force Poll** button → `POST ${base}/admin/outbox/poll`, disabled while
in flight, `MessageService` toast reporting `processed`, then an immediate
refresh rather than waiting for the next tick.

**Auto-refresh** — `interval(5000).pipe(switchMap(...), takeUntilDestroyed(...))`,
with `filter(() => document.visibilityState === 'visible')` so no request is
issued while the tab is hidden, `switchMap` so a slow response cannot overwrite
a newer one, and an immediate refresh on becoming visible. (Neighbouring
components use `takeUntil(this.destroy$)` — `user-admin.component.ts:14,81,87` —
which is the older idiom; `takeUntilDestroyed` is specified here because it is
the current Angular API and the component is new.)

**States.** 404 → "Outbox is disabled." as an informational card state, not an
error (the gate is a configuration, §1). 403 → "You do not hold MANAGER role."
Any other failure → the message in the card area, last good data kept and marked
stale. Success with no rows → "No outbox events yet."

**Nav.** A third item in the existing **Observability** group
(`app.component.ts:81-85`), after "Archived Transactions":
`{ label: 'Outbox', icon: 'pi pi-cloud-upload', route: '/observability/outbox', roles: ['MANAGER'] }`.
The group already exists (so does the `roles` field, `:75`, and
`hasRequiredRole` at `:98-102`), so nothing else is created — the item is
hidden from non-managers exactly like "Backoffice" (`:93`).

### 10. Demo script

Prerequisites: Postgres up on 5433 (`application.properties:104`), Kafka and
Zookeeper up (`docker-compose.yml:2-13`), `billing-service` up if the archive is
to be shown, ledger-service started with

`--ledger.outbox.enabled=true --ledger.publisher=kafka --ledger.outbox.poll-interval=60000`

All four flags matter. `ledger.publisher=kafka` is what creates the
`KafkaTransactionEventPublisher`/`KafkaTemplate` beans. The 60 s interval keeps
`PENDING` on screen: at the 5 s default a row created while Kafka is down is
attempted on the next tick and becomes `FAILED` (see Design notes), which would
make the screenshot a race. Force Poll drives the publish instead, so the demo
does not depend on where in the tick cycle you are.

| # | Action | Expected |
|---|---|---|
| 1 | Open **Observability → Outbox**; note the five counters | page loads; if it says "Outbox is disabled." the gate is off — fix the flag first |
| 2 | `docker stop kafka` (container name is `kafka`, `docker-compose.yml:13`) | producer errors in the ledger log are expected and must not fail a transfer |
| 3 | Post one transfer: `POST /ledger-service/api/transfers` with `{sourceAccountId, destinationAccountId, amount, transactionId}` (`TransferController.java:36-48`) | **200** — this is the request the old code would have reversed |
| 4 | Look at the page | **Pending +1**; the row is `PENDING`, `Attempts 0`, `Published At —`. In SQL: `published_at`, `parked_at` null, `attempt_count = 0`, and `saga_steps` has `ARCHIVE`/`EXECUTED` for the same `transaction_id` — the two halves of AC-1, committed together |
| 5 | `docker start kafka` | — |
| 6 | Press **Force Poll** | toast reports `1`; **Pending −1 / Published +1**; the row is `PUBLISHED` with a timestamp and **still `Attempts 0`** (AC-2) |
| 7 | (optional) read the topic | one `TRANSFER` record, key = the transaction id, payload equal to the stored one |

Failure story, which is the other half:

| # | Action | Expected |
|---|---|---|
| 8 | `docker stop kafka`; post a transfer; press **Force Poll** | **Failed +1**; row `FAILED`, `Attempts 1`, non-empty Last Error with the cause. The transfer itself still returned 200 |
| 9 | `docker start kafka`; press **Force Poll** | row becomes `PUBLISHED`, **Failed −1**, `Attempts` keeps its count — retry-then-success, visible |

**Note on manual SQL.** When manually inserting rows for testing or demos, use
`now() AT TIME ZONE 'UTC'` in your SQL script — never bare `now()`:

```sql
INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload, created_at)
VALUES ('…', 'TRANSACTION', 'tx-demo', 'TRANSFER', '{"transactionId":"tx-demo"}',
        now() AT TIME ZONE 'UTC');
```

The four `TIMESTAMP` columns hold **UTC wall-clock** values, because that is how
Hibernate maps an `Instant` onto `timestamp without time zone`. A bare `now()`
writes the server's *local* wall clock, so on a host at UTC+1 every hand-written
row reads as an hour in the future: `created_at` sorts into the future and a
`next_attempt_at` set that way makes the poller skip the row entirely until real
time catches up. This is a fixture trap, not a service behaviour — every timestamp
the application writes comes from its injected `Clock` and is already UTC, so the
relay and the API agree with each other. It only bites the human writing SQL.

## Acceptance criteria

- [ ] **AC-1 — dual write is closed, per step.** With the gate on and Kafka
      stopped, one transfer returns 200 and the archive transaction commits
      **both** halves: `saga_steps` gains `ARCHIVE`/`EXECUTED` for that transaction
      and `outbox_events` gains exactly one row for it with `published_at IS NULL`,
      `parked_at IS NULL`, `attempt_count = 0`. With the gate off, no outbox row is
      written and `publishStrict` is still called — the legacy path is unchanged.
- [ ] **AC-2 — relay, byte-identical.** With Kafka restarted, within
      `2 × poll-interval` that row has `published_at` non-null and
      `attempt_count = 0`, and the message on `ledger-events` has key = the
      transaction id with a payload identical to the stored `payload` and to the
      pre-change `KafkaTransactionEventPublisher` output (captured as a golden
      string from the gate-off path, which still calls that publisher).
- [ ] **AC-3 — rollback safety, scoped to the archive.** A failure thrown inside
      `OutboxArchiveStep.archive` **after** the outbox insert leaves **zero**
      outbox rows and **zero** `ARCHIVE` steps for that transaction, while the
      already-committed `DEBIT_SOURCE`/`CREDIT_DESTINATION` rows stay and are
      reversed by compensation (status `COMPENSATING`). Those two are not rolled
      back and must not be (§3.1). `simulateFailure=true`
      (`TransferSagaService.java:180-182`) throws *before* the insert and cannot
      exercise this; the test uses a throwing `ArchiveStep` double (§3.5), and the
      DB-level assertion is the manual check in §10 — the module has no DB test
      harness (§3.5).
- [ ] **AC-4 — poison parks, never drops.** A row whose publish fails
      `max-attempts` times ends with `parked_at` non-null, still present, the
      `outbox.parked.count` counter incremented **once**, and `status = PARKED`
      from `GET`; between attempts it is never `FAILED` with
      `attempt_count = max-attempts` and `parked_at IS NULL`, which would be a row
      neither claimed nor parked. Run the check with a low `max-attempts` so it
      does not take half an hour.
- [ ] **AC-5 — replay is absorbed.** Re-publishing the same outbox row leaves
      `archived_transactions` unchanged, because `billing-service` dedupes on
      `transactionId` in `TransactionEventConsumer.java:29,42-46`. Scope of that
      guarantee is recorded in Design notes (in-memory, per JVM).
- [ ] **AC-6 — API contract.** `GET` returns the exact six fields of §6 with the
      exact DTO names, and **no `payload` field at all** — not null, not opt-in;
      the counts satisfy `pending+published+failed+parked == total`. An
      unauthenticated call
      is **401** at the edge (`.anyExchange().authenticated()`,
      `gateway .../SecurityConfig.java:81`); an authenticated caller without
      `MANAGER` is **403** — from the service always, from the edge once Phase 5
      lands — and a direct call to port 8085 with no identity headers carries an
      empty `CallerContext` (`HeaderAuthFilter.java:26-29`) and is therefore also
      **403**. With `ledger.outbox.enabled=false`, **both** `GET` and `POST`
      return **404**.
- [ ] **AC-7 — Force Poll bypasses the timer.** With `poll-interval=60000`, a
      pending row publishes within 1 s of `POST /api/admin/outbox/poll` and the
      response's `processed` is ≥ 1.
- [ ] **AC-8 — the demo is reproducible from this document.** §10 runs
      end to end with no step that is not written there, including the two
      required flags.
- [ ] **AC-9 — no regressions.** `mvn test` passes, including the existing saga
      tests — which construct the service with `new` and must keep asserting the
      same compensation outcomes; they must **not** be moved into a Spring context
      to make a transaction appear (§3.1 BLK-1) — and the seeder path; `seed` runs
      with `ledger.seed.enabled=true` still produce **no** outbox rows, because
      the seeder writes saga rows directly and never enters the saga
      (`LedgerSeeder.java:645-656`); the `UserAdminComponent` Karma spec and the
      rest of the frontend suite pass; the new component ships a spec covering
      404 / 403 / empty / one-row.

## Constraints

- **Flyway owns the schema** (`AGENTS.md:31`, `application.properties:108`
  `ddl-auto=validate`). One additive migration, `V4`; no existing column, table
  or index is altered. `V4` was confirmed free (max is `V3`).
- **No new dependency, therefore no ADR.** `spring-boot-starter-actuator` is
  already a dependency (`ledger-service/pom.xml:36`) and brings Micrometer, so
  `MeterRegistry` and `Counter` need nothing added. And no test-infrastructure
  dependency is added either: asserting the rollback against a real database would
  need Testcontainers/H2, which this feature does **not** introduce (§3.5,
  **Out of scope**).
- **No `@Transactional` on `TransferSagaService`, on any overload** (§3.1). The
  only transactional member of this feature is `OutboxArchiveStep.archive`, a
  different bean, and the structure guard in §3.5 fails if that stops being true.
  Annotating the saga is not a simplification: on the overload the controller calls
  it never runs (BLK-1), and where it did run it would roll back the compensations
  (BLK-2).
- **Gate-off behaviour is unchanged, byte for byte.** With
  `ledger.outbox.enabled` absent or `false` the saga still calls
  `publishStrict` inline and still compensates on a Kafka failure, and no outbox
  row is written (§1, §3.2). The feature is additive by construction, not by
  disposition.
- **`spring-boot-starter-security` must not be added** (`AGENTS.md:26`;
  `HeaderAuthFilter.java:22-24`). Authorization is `CallerContext` +
  `ForbiddenException` only.
- **Feature gate default OFF** (`AGENTS.md:21`, `project-state.md:80`). This
  overrides the draft's `matchIfMissing=true`, which would have contradicted
  every existing gate in this service. `ledger.outbox.enabled=false` ships in
  `application.properties`, documented in the style of the four gates above it.
- **`ROLE_ADMIN` becomes `MANAGER`.** The realm has three roles
  (`realm-export.json:8-10`) and no `ADMIN`; `MANAGER` is the established
  privileged role. Deviates from the brief, deliberately, to avoid a Keycloak
  realm change inside this feature.
- **Timestamps are `TIMESTAMP`, not `timestamptz`.** The brief asked for
  `timestamptz`; every existing table uses `TIMESTAMP` (`V1:7`, `V2:9,23-24,33`)
  and the outbox is read alongside them. Consistency wins; the entity maps
  `Instant` fields the same way `EventEntity.java:59-60` already does.
- **No Kafka transactions in the poller.** The producer has no transactional id
  (`KafkaProducerConfig.java:24-39`) and the consumer is `read_committed`
  (`billing/KafkaConsumerConfig.java:44`); adding one would hide published rows
  from the consumer.
- **Kafka key stays the transaction id** (`KafkaTransactionEventPublisher.java:55`).
  Changing it to an aggregate id would re-partition the existing topic.
- **`docker-compose.yml` is not touched** (`AGENTS.md:24`); no `NewTopic` bean
  is added — `ledger-events` is broker-auto-created today.
- **`application.properties`, not `.yml`** — this service has no
  `application.yml`.
- **Additive configuration only**: no existing `ledger.*` property changes
  meaning, and `ledger.publisher` keeps its `http` default so an untouched
  checkout behaves exactly as before.

## Out of scope

- **CDC / Debezium.** No log tailing; the relay is a polling reader.
- **Exactly-once delivery.** At-least-once, as `billing-service`'s manual-ack
  consumer already assumes. Nothing in the UI claims otherwise.
- **Cross-service outbox.** `ledger-service` only.
- **The best-effort `publish(...)` path.** `POST /api/transactions` still calls
  `publish()` directly (`TransactionEventPublisher.java:9-11`); its dual-write
  window is unchanged. Only the saga's `publishStrict` is covered.
- **Modifying the Backoffice.** Its page is reused as a style reference and its
  code is untouched. The Outbox page lives under Observability.
- **Payload redaction or encryption.** The `payload` column is plaintext event
  data, retained indefinitely — a known gap, accepted for the demo and noted for
  a security review before any non-demo deployment. Redaction is a
  *publisher-side* decision; it must never be implemented by rewriting stored
  rows, which would break byte identity.
- **`billing-service` changes and durable dedupe.** Not touched. See the gap in
  Design notes.
- **Retention / partitioning** of published rows; **replay or unpark** tooling;
  **alerting** on `outbox.parked.count`.
- **Multi-instance ordering machinery.** Not needed while one row per key
  exists; the invariant that preserves that is stated in §5.
- **Gateway `/api/admin/**` rule** — delivered Phase 5, not here.
- **Payload inclusion in the UI.** The dashboard never asks for it.
- **A database-backed test harness (Testcontainers/H2).** §3.5's rollback
  assertion is structural plus the manual `§10` check; adding the harness would be
  its own project and is the follow-up this gap is named for.

## Design notes

### Status derivation (one rule, two consumers)

Evaluate in order; first match wins:

| Order | `status` | Condition |
|---|---|---|
| 1 | `PUBLISHED` | `published_at IS NOT NULL` |
| 2 | `PARKED` | `published_at IS NULL AND parked_at IS NOT NULL` |
| 3 | `FAILED` | `published_at IS NULL AND parked_at IS NULL AND attempt_count > 0` |
| 4 | `PENDING` | `published_at IS NULL AND parked_at IS NULL AND attempt_count = 0` |

Order matters: a row that succeeded on its third attempt has `attempt_count = 3`
and a non-null `published_at`, and must read `PUBLISHED`. The counters come from
the same predicates — four count queries, one per branch of this table, plus
`count()` for the total — so the invariant holds by construction rather than by
coincidence.

### Why `parked_at` on the same table, not a dead-letter table

Parking must be one atomic `UPDATE` inside the poller's existing transaction — the
same statement that records the final failed attempt (§5.3). A separate table means
insert-then-delete across two tables: a crash between them either duplicates the
event or loses it — reintroducing the exact defect this feature removes, in its own
cleanup path. Keeping rows also keeps one query for the four statuses and the
counters, and keeps a parked row readable in the same list where an operator needs
it.

### Why parking is the final attempt's own `UPDATE` (COR-2)

Three distinct failures are avoided by not issuing a second statement for the same
row:

- **A self-deadlock.** A parking `UPDATE` in `REQUIRES_NEW` opens a second
  connection while the claim transaction still holds that row's lock. The second
  transaction waits for the first, the first will not finish until its batch is
  done, and PostgreSQL kills the pair at `deadlock_timeout`. A separate parking
  sweep that orders rows differently from the claim produces the classic AB/BA
  deadlock *between* pollers instead. The claim statement already locked the row,
  so one `UPDATE` on the same connection acquires nothing and cannot deadlock.
- **A row that is never retried and never parked — the worse bug.** If the attempt
  update commits and the park does not (crash, connection loss, rollback of a
  `REQUIRES_NEW` sibling), the row sits at `attempt_count = maxAttempts` with
  `parked_at IS NULL`. The claim predicate (`attempt_count < :maxAttempts`) then
  never selects it again, and the status rule reports `FAILED` forever — invisible,
  unpublishable and parked by nothing. That is exactly the "never drops" guarantee
  of AC-4 failing silently, and it is why this is a correctness rule and not a
  performance tweak.
- **An over-counted metric.** Incrementing `outbox.parked.count` on the same
  statement's affected-row report increments it once per real transition; a
  re-entrant or retried park path could count the same row twice.

### Why `published_at` comes from the injected `Clock` (COR-3)

`TimeConfig.java:20-22` exists so time is injected, and the poller is the code
where that pays off most: `1s, 2s, 4s … 300s` backoff is only assertable with a
pinned clock, and a mixed implementation — Java `Instant.now()` for one stamp,
SQL `now()` for another — puts two clocks on one row and makes both the backoff
tests and any after-the-fact reasoning about a parked row unreliable. So every
poller-written timestamp (`published_at`, `parked_at`, the `next_attempt_at` base,
and the claim predicate's `now`) is `clock.instant()`. The one exception is
`created_at`, which is the saga's business time (`archiveAt`, §4) — deliberately a
different clock, because a backfilled row must sort where it happened.

### Why backoff, and why it is not optional here

Without `next_attempt_at` the poller retries every 5 s and exhausts 10 attempts
in under a minute, so a one-minute Kafka blip parks perfectly healthy rows and
every blip needs a manual unpark. With exponential backoff
(`1s, 2s, 4s … 300s`) the budget spans roughly 30 minutes, which is what makes
`PARKED` mean "this event is genuinely bad". Cost: one nullable column and one
predicate.

### Why the byte-identity rule forces `TEXT` and verbatim sends

`AC-2` compares bytes against the pre-change publisher. `jsonb` reorders keys
and normalizes whitespace, so storing there would guarantee a different payload
for at least some records. Sending the stored string unchanged also removes the
two-serializers-must-agree failure mode: `payload` *is* the message body.

### Known races and gaps

- **`PENDING` is short-lived at the default interval.** `FAILED` means
  "attempted and not yet published", so a row created during an outage flips to
  `FAILED` on the first tick, ~5 s later. This is correct — the UI shows a
  retry in progress — but it makes "see Pending" a race at 5 s, which is why
  §10 prescribes `poll-interval=60000` plus Force Poll. (Rejected alternative: a
  fifth property that pauses the relay while keeping the API alive, purely to
  make a screenshot prettier.)
- **`billing-service` dedupe is in-memory** (`TransactionEventConsumer.java:29`,
  a `ConcurrentHashMap.newKeySet()`; the class comment at `:18-19` says "swap
  for Redis in production"). Within a running JVM a replayed event is skipped,
  so AC-5 holds; across a restart the set is empty and `auto-offset-reset=earliest`
  (`KafkaConsumerConfig.java:47`) re-reads the topic, and `transactionId` has no
  unique constraint (`ArchivedTransaction.java:31`) — so duplicates are
  possible. Recorded as a **GAP for a follow-up spec**, deliberately not fixed
  here: this feature is at-least-once and the consumer already acknowledges that
  contract.
- **Long claim transactions during an outage.** Bounded by aborting the batch on
  the first broker-level failure (§5.4). Without that, `batch-size=50` ×
  `delivery.timeout.ms=10000` would hold row locks for over eight minutes.
- **The terminal status is outside the archive transaction** (§3.3). A crash
  between the archive commit and `persist()` leaves a non-terminal saga row whose
  outbox row exists and will still be published. Accepted deliberately: putting
  `COMPLETED` inside the archive transaction would also put it out of reach of the
  failure path, which must write `COMPENSATING` *after* a rolled-back archive. The
  saga row is left as evidence and the event is not lost — the correct side of that
  trade for this feature.
- **The rollback assertion is structural, not database-backed** (§3.5). This
  module has no H2/Testcontainers harness (`ledger-service/pom.xml:55-56,74`), so
  AC-3's DB-level half is verified manually in §10 and guarded in code by the
  structure test. Adding the harness is a worthwhile follow-up and is out of scope
  here — recorded so the gap is a decision, not an oversight.

### Codebase facts this spec depends on

| Fact | Evidence |
|---|---|
| max migration is `V3`, so `V4` is free | `db/migration/V1..V3` |
| the saga has no transaction today, deliberately | no `@Transactional` in `TransferSagaService.java` (§3.1); per-method ones at `JpaEventStore.java:59,65`, `JpaSagaRepository.java:44`, `JpaJournalEntryRepository.java:34,40` |
| the saga's HTTP entry point is the **2-arg** overload; the 4-arg one is reached by self-invocation | `TransferController.java:43`; `TransferSagaService.java:75-76,80-81,94` |
| the only `@Transactional` that ever wraps saga writes is each collaborator's own | `JpaEventStore.java:59,65`; `JpaSagaRepository.java:44`; `JpaJournalEntryRepository.java:34,40` |
| the seeder writes saga rows directly and never calls the saga | `LedgerSeeder.java:645-656`, `:667-689`; no reference to `TransferSagaService` or the publisher in the file |
| the seeder's whole backfill is one programmatic transaction | `LedgerSeeder.java:345`, `:939-954` |
| existing saga tests build the service with `new` (no proxy, no context) | `TransferSagaServiceFundsCheckTest.java:39-64` |
| the module has no DB-backed test harness | `ledger-service/pom.xml:55-56` (Postgres, runtime), `:74` (starter-test); no H2, no Testcontainers |
| `SagaState` exposes three `addStep` overloads, so a copy is constructible | `SagaState.java:38-57`; `JpaSagaRepository.java:44-72` replaces the step collection on save |
| `aggregate_id` is a string, `VARCHAR(255)` | `V1:3`, `EventEntity.java:47-48`, `V2:17` |
| UUIDs are `VARCHAR(36)` generated in Java | `V2:2`, `TransferSagaService.java:326-328` |
| timestamps are `TIMESTAMP` | `V1:7`, `V2:9,23-24,33`, `EventEntity.java:59-60` |
| topic is `ledger-events`, key is the transaction id, JSON body, no headers | `KafkaTransactionEventPublisher.java:26,54-55,17-19` |
| the publisher's live-stream mirror fires after broker ack, from both paths | `KafkaTransactionEventPublisher.java:78-91` |
| config lives in `application.properties`, `ledger.*` | `application.properties:25-99` |
| gate-opens are exact complements on one key (precedent for `ArchiveStep`) | `application.properties:44-46`; `NoOpLiveEventBroadcaster` / `StompLiveEventBroadcaster` |
| every existing gate defaults OFF | `application.properties:52,75,84,94`; `AGENTS.md:21` |
| gateway has no `/api/admin/**` rule | `gateway-service .../SecurityConfig.java:66-81` |
| routing is by discovery locator, no explicit route | `gateway .../application.properties:7`; `application.yml:1-13` |
| gateway CSRF is disabled | `SecurityConfig.java:64` |
| realm roles are TELLER/MANAGER/AUDITOR; no ADMIN | `keycloak/realm-export.json:8-10` |
| ledger-service has no Spring Security | `HeaderAuthFilter.java:22-24` |
| `/ledger-service` proxy already exists | `ecom-frontend/proxy.conf.json:37-45` |
| Observability nav group already exists | `app.component.ts:81-85` |
| `roleGuard` exists; parent route has `authGuard` | `guards/role.guard.ts:6-19`; `app.routes.ts:40,66` |
| `@EnableScheduling` and a `Clock` bean exist | `LedgerServiceApplication.java:10`; `TimeConfig.java:20-22` |
| Micrometer available, no counter exists yet | `ledger-service/pom.xml:36` |
| billing dedupes in memory on `transactionId` | `TransactionEventConsumer.java:29,42-46` |
| `simulateFailure` throws before the publish site | `TransferSagaService.java:180-182` |
| compensations must be ordered and audited | `AGENTS.md:18` |
| Kafka container is named `kafka` | `docker-compose.yml:13` |

### Delivery plan

**Phase 0 — verification: COMPLETE.** Every item above was read from this
working tree; there are no unverified claims left in this document. The two
questions that would have blocked implementation are answered: `V4` is free, and
the saga is confirmed non-transactional — which §3.1 then turned from an
observation into a requirement.

**Phase 0.5 — review findings: APPLIED to this document.** The first draft was
wrong in two blocking ways and stale in three correctable ones. This revision is
the repair; the entries below are the audit trail for it.

| Finding | What the draft said | What this revision says |
|---|---|---|
| **BLK-1** — self-invocation bypasses the proxy | `@Transactional` on `execute(request, simulateFailure, occurredAt, publish)` (`:94`), asserted to cover the call graph | no annotation on the saga at all; the boundary is `OutboxArchiveStep`, a separate bean injected as `ArchiveStep` (§3.2) |
| **BLK-2** — a saga-wide transaction destroys `COMPENSATING` | one transaction around the whole saga, compensations "joining" it | per-step transactions preserved and documented as required; only `ARCHIVE` + outbox row are atomic (§3.1, §3.3) |
| **COR-1** — stale seeder rationale | "the seeder calls `execute(..., false)`" | the seeder never enters the saga (`LedgerSeeder.java:645-656`, `:345`); the flag's actual contract stated, AC-9 restated (§4) |
| **COR-2** — parking deadlock | parking as a follow-up `UPDATE` after `maxAttempts` | parking inside the final failed attempt's single `UPDATE`, one connection, one statement (§5.3, Design notes) |
| **COR-3** — `published_at` from `now()` | `now()` in the success branch | `clock.instant()` for every poller stamp, claim predicate included (§5) |

| Phase | Work | Exit gate |
|---|---|---|
| 1 | `V4__create_outbox_events.sql` + `OutboxEvent` entity (must satisfy `ddl-auto=validate`) | migration applies; context starts |
| 2 | `ArchiveStep` seam + both gate-selected implementations; `OutboxArchiveStep` (`@Transactional`: archive row + outbox insert); `SagaState.copyWithStep`; `streamStep` extraction; golden payload captured first | AC-1, AC-3 |
| 3 | `OutboxPublisher`: claim, publish, one-`UPDATE` bookkeeping, backoff, park-in-the-same-`UPDATE`, `clock.instant()` stamps, live-stream mirror, `poll()` + thin `@Scheduled`, gates, fail-fast, counter | AC-2, AC-4 |
| 4 | `OutboxAdminController`: `GET`, `POST`, DTOs, status rule, payload opt-in, `MANAGER` check | AC-6, AC-7 |
| 5 | `gateway-service` `SecurityConfig` rule for `/ledger-service/api/admin/**` | 403 at the edge for a teller |
| 6 | Angular component, route, nav item, service method, spec | AC-8 |
| 7 | Regression: `mvn test`, Karma, seed run produces no outbox rows | AC-9 |

Phase 2 is the one with a structural exit gate as well as a behavioural one: the
three tests of §3.5 land with it, and the structure guard is what keeps a later
"cleanup" from folding the transaction back into the saga (BLK-1).
