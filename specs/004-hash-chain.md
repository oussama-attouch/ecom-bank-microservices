# Feature: Event-Store Hash Chain (Tamper Evidence)

Verified against the working tree at write time (branch `feature/hash-chain`, cut
from `main` at `44aa850`). Every `path:line` below was read from this repository,
not inferred.

**The four decisions everything else follows from.** They are stated up front
because each one is a fork in the road, not a detail, and each is argued in its
own section: the chain is forward-only, because the 51,042 rows that predate the
migration cannot acquire an honest digest (§2.4); the write path takes an
advisory lock, because two concurrent appends would otherwise fork the chain
(§3.3); the digest covers the stored payload bytes and nothing that is not in
them (§3.1, and the gap that leaves is recorded in Out of scope); and the
verification endpoint is gated and `MANAGER`-only while hashing itself is always
on (§1, §6).

## Business context

**Module:** `ledger-service` (one additive migration, the append path, a
verification service, one admin endpoint), `ecom-frontend` (one page). No change
to `gateway-service` — the rule it needs already exists (§Services impacted).

**Role:** `MANAGER`, the same role every other `/api/admin/**` endpoint in this
deployment requires. See §6.

**Problem:** the event log *is* the ledger, and only convention protects it.

`event_store` is append-only because no code issues an `UPDATE`
(`JpaEventStore.java:29-31`: *"Events are never updated or deleted — only
inserted"*). Nothing in the database enforces that sentence. A database
administrator can run

```sql
UPDATE event_store SET payload = … WHERE id = 37412;
```

and every existing integrity check still passes:

- `LedgerIntegrityChecker` asserts `totalDebits == totalCredits` on startup
  (`LedgerIntegrityChecker.java:26-31`). That invariant survives any edit made
  consistently to both legs of a transfer — which is the shape a fraudulent edit
  takes.
- `POST /api/admin/projections/rebuild` compares a Java replay of the log against
  a SQL aggregate over the same log (`ProjectionAdminController.java:47-64`). Two
  computations over the *same* rows cannot disagree about a row that was edited
  before either read it.
- Every projection, chart and KPI folds the same rows, so the whole read side
  agrees with the tampered past instead of contradicting it.

Both checks answer *"is the ledger internally consistent?"*. Neither can answer
*"is this the ledger that was written?"*, because both take the stored rows as
given. A bank needs the second question answered: tamper-evident storage is what
separates a ledger from a table.

A hash chain supplies that missing dimension cheaply. Each event stores a SHA-256
digest of its own payload combined with its predecessor's digest, so the stored
history is checkable against itself: any in-place edit, insertion or deletion
inside the chain produces a discontinuity at a specific event id, which one
endpoint can find and one page can show.

## Services impacted

- **`ledger-service`** — migration `V5`, two columns on `EventEntity`, a hashing
  helper, a chain-lock component, the append change, `HashChainService`, and
  `AuditVerificationController`. One new property block in
  `application.properties`. No new dependency (`java.security.MessageDigest` and
  `HexFormat` are JDK; Java 21 is the module's level, `ledger-service/pom.xml:17`).
- **`ecom-frontend`** — one standalone component, one route, one nav item, one
  service method. `proxy.conf.json:37-45` already proxies `/ledger-service`, so
  no proxy change.
- **`gateway-service`** — **no change.** `SecurityConfig.java:78` already maps
  `.pathMatchers("/ledger-service/api/admin/**").hasRole(MANAGER_ROLE)`, added for
  the outbox; `/api/admin/audit/verify` is under that prefix, so the edge refuses
  first exactly as it does for `/api/admin/outbox`.
- **Not impacted:** `billing-service` (it consumes `ledger-events` and never reads
  the ledger's tables), `customer-service`, `inventory-service`, `order-service`,
  `config-service`, `discovery-service`, `keycloak/`, `docker-compose.yml`.

Files to create or edit, enumerated before implementation as the workflow
requires (`docs/CONTEXT/project-state.md` §3):

| File | Change |
|---|---|
| `ledger-service/src/main/resources/db/migration/V5__add_hash_chain_to_event_store.sql` | new — §2.2 |
| `ledger-service/.../persistence/entity/EventEntity.java` | +`hash`, +`previousHash` |
| `ledger-service/.../persistence/repository/EventJpaRepository.java` | +head read, +paged verification read |
| `ledger-service/.../persistence/JpaEventStore.java` | hash in `append` (§3.2) |
| `ledger-service/.../audit/EventHasher.java` | new — the one hashing rule |
| `ledger-service/.../audit/HashChainLock.java` | new — the advisory lock (§3.3) |
| `ledger-service/.../audit/HashChainService.java` | new — the walk (§4), gated |
| `ledger-service/.../audit/AuditVerificationController.java` | new — the endpoint (§5), gated |
| `ledger-service/.../audit/AuditVerificationResponse.java` | new — the three-field record |
| `ledger-service/src/main/resources/application.properties` | +`ledger.audit.enabled=false` block |
| `ecom-frontend/src/app/components/observability/audit/` | new — `.ts`/`.html`/`.scss`/`.spec.ts` |
| `ecom-frontend/src/app/services/ledger.service.ts` | +`verifyChain()` |
| `ecom-frontend/src/app/app.routes.ts` | +import, +route |
| `ecom-frontend/src/app/app.component.ts` | +nav item |
| `ledger-service/src/test/.../audit/` | new — hasher vectors, walk cases (§4) |

No gateway, proxy or Compose file appears: the first rule already exists and the
last two need no route.

## Expected behavior

### 1. The feature gate

| Property | Default in `application.properties` | Effect when not `true` |
|---|---|---|
| `ledger.audit.enabled` | `false` | no `HashChainService` bean, no controller bean, **`/api/admin/audit/verify` is 404**; no full-table walk exists to be triggered |

`@ConditionalOnProperty(name = "ledger.audit.enabled", havingValue = "true")` on
the controller **and** on the service, with **no `matchIfMissing`** — the shape
`ProjectionAdminController.java:38` and `OutboxAdminController.java:48` use. Both
carry it because the controller's only collaborator is the walker: one property
gates the pair, so there is no configuration in which the route exists without
the walker or the walker idles without a caller.

`@Profile("!inmem")` on both, for the reason `ProjectionAdminController.java:31-34`
records: the walk reads `event_store`, which does not exist without a database.
In the `inmem` profile the route is a plain 404.

**The write-side hashing is deliberately *not* gated**, and this is the one place
this feature departs from "absent, not idle" (`AGENTS.md:21`). The gate exists to
keep optional *surfaces* absent — an endpoint, a scheduled thread, a full-table
scan. A chain with holes is not a chain: if hashing were switched on later, every
event written before the switch would be permanently unverifiable, and the
verification answer would depend on a deployment's configuration history. So
`append` always hashes (one SHA-256 per event, ~1µs), and the flag toggles only
the audit surface. The property block in `application.properties` states this in
the same register as the existing gates (`:77-84`, `:101-112`).

### 2. Data model — `V5__add_hash_chain_to_event_store.sql`

**`V5` is free.** The migrations in the tree are `V1__create_event_store.sql`,
`V2__create_journal_and_saga_tables.sql`, `V3__add_seed_indexes.sql` and
`V4__create_outbox_events.sql` — listed from
`ledger-service/src/main/resources/db/migration/`, not assumed. `V2` does not
touch `event_store`; `V3` only adds `idx_event_store_occurred_at` (`:4`).

#### 2.1 The columns as they are today

`V1__create_event_store.sql:1-11`:

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `BIGSERIAL` | NO | PK; the global log position |
| `aggregate_id` | `VARCHAR(255)` | NO | the account id |
| `sequence_number` | `BIGINT` | NO | position within the aggregate |
| `event_type` | `VARCHAR(255)` | NO | the event class's simple name |
| `payload` | `TEXT` | NO | the serialized event, verbatim |
| `occurred_at` | `TIMESTAMP` | NO | business time (UTC wall clock) |

Constraints/indexes: `uq_event_store_aggregate_sequence (aggregate_id,
sequence_number)` (`V1:8`), `idx_event_store_aggregate_id` (`V1:10`),
`idx_event_store_event_type` (`V1:11`), `idx_event_store_occurred_at` (`V3:4`).

#### 2.2 The migration

```sql
-- Tamper-evident event log (specs/004-hash-chain.md §2).
--
-- hash is SHA-256(previous_hash || payload) as lowercase hex: 64 characters,
-- computed by EventHasher over the exact bytes stored in payload. previous_hash
-- is the hash of the preceding row in id order, or the literal GENESIS for the
-- row that starts a chain.
--
-- Nullable on purpose, and both columns together: 51,042 rows written before
-- this migration have no digest and cannot acquire an honest one (a hash
-- computed now attests to what the table says today, not to what was written).
-- The chain therefore starts at the first row appended after V5. See §2.4 and
-- Out of scope.

ALTER TABLE event_store ADD COLUMN hash          VARCHAR(64);
ALTER TABLE event_store ADD COLUMN previous_hash VARCHAR(64);

ALTER TABLE event_store
    ADD CONSTRAINT ck_event_store_hash_format
        CHECK (hash IS NULL OR hash ~ '^[0-9a-f]{64}$'),
    ADD CONSTRAINT ck_event_store_previous_hash_format
        CHECK (previous_hash IS NULL
               OR previous_hash = 'GENESIS'
               OR previous_hash ~ '^[0-9a-f]{64}$'),
    -- Both or neither: a row with a digest but no predecessor (or the reverse)
    -- describes a chain link that cannot be verified, so the database refuses it.
    ADD CONSTRAINT ck_event_store_hash_pair
        CHECK ((hash IS NULL) = (previous_hash IS NULL));

-- One successor per event: a linear chain uses each digest exactly once, so a
-- second row claiming the same predecessor is a fork. Postgres treats NULLs as
-- distinct in a unique index, so the pre-migration rows are unaffected.
CREATE UNIQUE INDEX uq_event_store_previous_hash ON event_store (previous_hash);

COMMENT ON COLUMN event_store.hash IS
    'SHA-256(previous_hash || payload), lowercase hex (specs/004-hash-chain.md)';
COMMENT ON COLUMN event_store.previous_hash IS
    'hash of the preceding event in id order, or GENESIS for the chain start';
```

| Column | Type | Null | Notes |
|---|---|---|---|
| `hash` | `VARCHAR(64)` | YES (until V6) | lowercase hex SHA-256; `CHECK` enforces the form |
| `previous_hash` | `VARCHAR(64)` | YES (until V6) | 64-hex digest, or `GENESIS`; `CHECK` enforces both forms |

**No new index for the walk.** Verification reads the table in `id` order, which
is the primary key's order; a sequential scan or an index scan of the PK is what
it wants. An index on `hash` would serve nothing this feature queries.

**No `NOT NULL` yet.** Making the columns `NOT NULL` requires every row to have a
value, which is exactly what §2.4 declines to fabricate. Adding the constraint is
the *contract* half of expand/contract and belongs in a later `V6` once every
deployment has either reseeded or backfilled. `V6` is out of scope here; until
then the verifier enforces the same invariant on the way past, and reports a hole
in the chain as `BROKEN` rather than tolerating it.

`spring.jpa.hibernate.ddl-auto=validate` (`application.properties:129`) means the
entity must gain the two fields in the same commit as `V5`, or the context fails
at startup — the correct order, since a validate failure is louder than a missing
column at first insert.

#### 2.3 The genesis event

The first event of a chain stores `previous_hash = 'GENESIS'`, and everything
after it stores its predecessor's digest.

- **`GENESIS` cannot collide with a digest.** It is not hex and is not 64
  characters, so no SHA-256 output can equal it. A sentinel that *could* collide
  would let a row masquerade as a chain start.
- **There is exactly one.** `uq_event_store_previous_hash` allows one `GENESIS`
  row per table. Two genesis rows mean two chains, which is precisely what a
  partial wipe followed by fresh appends leaves behind — and the verifier reports
  the second one as `BROKEN` (§4).
- **It is "no predecessor", not "the beginning of time".** On the seeded
  portfolio database the genesis row is the first event appended *after* `V5`;
  the 51,042 rows below it are the legacy prefix (§2.4). The verifier does not
  pretend otherwise: `eventsChecked` counts chain events, not table rows.

#### 2.4 The 51,042 rows that predate the chain

`README.md:213` records the seeded portfolio ledger at **51,042 events**. They
have no hash and no way to acquire a trustworthy one: a digest computed during
the migration attests to what the table says *at migration time*, which is the
same thing a freshly seeded table would attest to — it cannot testify about the
past.

Three ways to handle that, decided rather than deferred:

| Option | Verdict |
|---|---|
| Backfill in `V5` with `pgcrypto` (`digest(previous_hash \|\| payload, 'sha256')` in a PL/pgSQL loop) | **Rejected.** It requires `CREATE EXTENSION pgcrypto`, which is a privilege this service does not otherwise need and Postgres is not even provisioned by `docker-compose.yml` (see `ARCHITECTURE.md:111`). It also puts a second implementation of the hashing rule in the repository: if the SQL and the Java ever disagree, every row reports `BROKEN` on a healthy log. |
| Backfill at startup in Java | **Rejected for this iteration.** It is a 51k-row write outside the append path, in a transaction of minutes, run once per deployment, and it still cannot testify about the past while it widens the write path this feature is trying to keep narrow. Recorded in Out of scope with the reseed shortcut below. |
| **Forward-only chain, and say so** | **Chosen.** The chain starts at the first post-`V5` append. Everything after that point is tamper-evident, which is the property the feature is for; the legacy prefix is documented, visible in `eventsChecked`, and one reseed away from disappearing. |

The reseed shortcut, for a database that must be fully covered: wipe and re-run
the seeder (`--spring.profiles.active=seed --ledger.seed.enabled=true`,
`application.properties:86-95`). The seeder appends through `EventStore` — the
`SeedLedger` decorator keeps an in-memory index and delegates every write
(`SeedLedger.java:51-60`) — so a seed on a wiped database produces a chain over
the whole log, and `eventsChecked` then equals `COUNT(*)`. The seeder is additive
(`SeedLedger.java:46-49`), so seeding an existing database extends the current
chain rather than starting a second one.

### 3. Write path — `JpaEventStore.append(...)`

The append logic lives in one place: `JpaEventStore.append(List<Event>)`
(`:58-62`) delegates to `JpaEventStore.append(List<Event>, Instant)`
(`:64-113`), which restamps (`:74`), assigns per-aggregate sequence numbers
(`:79-97`), builds the rows (`:88-95`), saves them (`:99`) and hands back log
offsets (`:102-112`). Every write path in the service reaches it:
`TransferSagaService.java:151,165,237,253`, `AccountService.java:59`,
`TransactionController.java:76`, and the seeder through `SeedLedger`.

That single funnel is why hashing one method covers the whole service.

#### 3.1 What is hashed, exactly

```
hash = lowercase_hex( SHA-256( utf8( previous_hash || payload ) ) )
```

- `payload` is **the exact string being stored** in the `payload` column —
  `serializer.serialize(event)` (`EventSerializer.java:23-29`), the same value
  that goes to the row. The digest is never taken over a re-serialization: the
  payload is `TEXT` and is read back verbatim, so writer and verifier hash the
  same bytes. (This is the same argument `V4__create_outbox_events.sql:12-18`
  makes for storing the outbox body as `TEXT`.)
- Serialization happens **after** the existing restamp (`:74`), so a backdated
  event's timestamp is inside the hashed bytes. Restamping after hashing would
  leave the stored payload and the stored column disagreeing with the digest.
- `previous_hash` is the predecessor's stored value, read from the column, never
  reconstructed. Its length (64 hex chars, or the 7-character `GENESIS` sentinel
  which only ever starts a chain) makes the concatenation boundary unambiguous,
  so no delimiter is required — and adding one would be a second encoding rule
  for the writer and verifier to keep in sync.
- Output is lowercase hex: `HexFormat.of().formatHex(MessageDigest…)`, 64
  characters, matching `ck_event_store_hash_format`. Test the algorithm with the
  published empty-string vector
  (`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`) rather
  than against itself.

#### 3.2 The change in the method

```java
@Transactional
public List<Event> append(List<Event> events, Instant occurredAt) {
    …
    chainLock.acquire();                                    // §3.3
    String previousHash = repository.findFirstByHashIsNotNullOrderByIdDesc()
            .map(EventEntity::getHash)
            .orElse(EventHasher.GENESIS);

    for (Event event : events) {
        String payload = serializer.serialize(event);
        String hash = EventHasher.sha256Hex(previousHash + payload);
        entities.add(new EventEntity(null, aggregateId, sequence,
                event.getClass().getSimpleName(), payload,
                event.getOccurredAt(), hash, previousHash));
        previousHash = hash;                                // the batch is one chain
    }
    List<EventEntity> saved = repository.saveAll(entities);
    …
}
```

Four properties of that shape are the whole guarantee:

1. **The batch chains to itself.** Every event links to the one before it in the
   same `append` call; only the first links to the stored head. Both shapes in the
   tree go through that loop: `TransactionController.transfer` builds the debit and
   the credit as one list and appends it once (`:105-117`, `:76`), while the saga
   appends them in two calls (`TransferSagaService.java:151,165`) whose separate
   transactions let the second read the head the first committed — so neither the
   batch nor the call boundary leaves a link unset.
2. **Chain order equals `id` order.** `EventEntity` uses
   `GenerationType.IDENTITY` (`EventEntity.java:43-45`), so Hibernate executes one
   `INSERT` per entity, in list order, and the database assigns each `id`
   immediately. The order the loop chains in is the order the rows land in.
3. **The head is read, not remembered.** No in-memory field caches the last
   digest: a restart, a restored backup or a concurrent writer cannot leave the
   application holding a head the table does not have.
4. **Nothing else changes.** Sequence numbers, restamping, broadcast and the
   returned offsets are untouched; the digest is a storage concern and never
   reaches the domain model (`Event`/`EventStore` gain no member), so no read path
   can accidentally depend on it.

`EventEntity` gains two fields (`hash`, `previousHash`) and therefore a longer
Lombok `@AllArgsConstructor` (`:40`). The only construction site in the tree is
`JpaEventStore.java:88-95` — `grep "new EventEntity"` returns exactly that one
line — so the compiler finds every caller. No test constructs `JpaEventStore`
(the suite uses the `EventStore` interface or hand-written doubles, e.g.
`InstantOverloadsTest.java:193-233`), so the write-path change cannot break
existing tests by construction; the new tests are the risk (§Acceptance criteria).

#### 3.3 Concurrency — one chain writer at a time

> **Invariant: at most one append may be inside its head-read → commit window at
> any moment.**

Without it, two appends read the same head, both chain onto it, and the table
holds two rows claiming the same predecessor: a fork, where both branches verify
individually but neither is *the* chain. The commit order can also differ from the
`id` order, which the verifier cannot distinguish from a tampered log.

The mechanism is **one advisory transaction lock**, taken on a constant,
documented key at the top of the append transaction:

```sql
SELECT count(*) FROM (SELECT pg_advisory_xact_lock(:key)) AS chain_lock
```

The collaborator that issues it (`HashChainLock`) carries `@Profile("!inmem")`,
like the store that injects it: in that profile there is no table and no chain.
The lock is released by Postgres at commit or rollback, needs no cleanup path, and
lives outside `event_store`'s lock space, so readers — every dashboard query — are
untouched. The `count(*)` wrapper gives the statement a mappable `bigint` result
instead of depending on how JDBC surfaces a function that returns `void`. No other
advisory lock exists in this service (`grep pg_advisory` returns nothing today);
a future feature must not reuse the key.

Cost, with numbers: the lock is held for one head `SELECT` and `saveAll`'s
`INSERT`s. A transfer appends two events (`TransferSagaService.java:151,165`), so
a ~2ms transaction puts the ceiling at roughly 250–500 transfers/s — three orders
of magnitude above what this service is asked to do, and the price of the only
guarantee that makes the chain linear.

Rejected alternatives, and why they are not equivalent:

| Alternative | Why not |
|---|---|
| `SELECT … ORDER BY id DESC LIMIT 1 FOR UPDATE` | Row locks do not serialise *inserts*. Both transactions read the same last row and both insert after it; the lock protects a row neither of them is updating. It looks like a lock and is not one. |
| `LOCK TABLE event_store IN EXCLUSIVE MODE` | Correct, and a bigger hammer: a table-level lock on every append, taken in the event table's own lock space, where a future long reader or migration interacts with it. |
| A single-row `chain_head` table with `FOR UPDATE` | Works, but adds a mutable row that can disagree with `event_store` (restored backup, truncated table) — a second source of truth for the head, which is exactly the class of bug this feature exists to remove. |
| Unique index alone, with retry on violation | Correct but turns a routine append into an exception-and-retry on a fraction of writes. The index is kept as an *assertion* (§2.2): if a future code path ever bypasses the lock, a fork becomes a `duplicate key` at insert instead of a silently forked history. |

**Consequence for the seeder.** `LedgerSeeder` writes its 51k events inside one
programmatic transaction (`LedgerSeeder.java:345`, `:939-954`), so the advisory
lock is held for the whole seed. Reads are unaffected (an advisory lock blocks
only other lockers), and the seed runs at startup on a database nobody is
transferring against yet; a live append during a seed waits rather than forking.
That is the same single-writer wait the chain implies everywhere.

### 4. Verification — `HashChainService`

`HashChainService.verify()` walks the entire table from oldest to newest,
recomputing every digest. Rules, in the order the walk applies them:

1. **Read `event_store` in `id` order**, pages of 1,000 rows (a constant, not a
   property: it is not a knob an operator has a reason to turn), projecting only
   `id`, `payload`, `hash` and `previous_hash`. Not entities: the walk never needs
   the JPA identity map, and 51k managed entities is memory for nothing.
2. **Leading rows with `hash IS NULL` are the legacy prefix (§2.4).** They are
   skipped and not counted — nothing can be verified about them, and claiming
   otherwise would be the one dishonest number on the page.
3. **The first row with a digest must carry `previous_hash = 'GENESIS'`.** Any
   other value means its predecessor is missing or its link was rewritten.
4. **A `NULL` hash *after* the chain has started is a break.** A hole inside the
   chain is exactly what an attacker produces by nulling a digest to escape
   verification, so it is `BROKEN`, never skipped.
5. **For every later row, `previous_hash` must equal the previous row's stored
   `hash`.** This is the chain half of the check, and it is what detects a row
   that was edited *and* had its own digest recomputed.
6. **Recompute and compare:** `sha256Hex(previous_hash + payload) == hash`. This
   is the per-row half, and it is what detects an edit that left the links alone.
7. **Stop at the first failure** and report it. The earliest discontinuity is the
   one that matters — everything after it is downstream of the damage, and a
   late rewrite can mask an earlier edit. A single `brokenAtEventId` can only name
   one row, so the contract (§5) is built around the first.

**`eventsChecked`** is the number of chain events verified, *including* the row
that failed — so on `BROKEN` it is the failing row's 1-based position in the
chain, and the UI can say "broke at the 37,412th event". On `INTACT` it is every
chain event. Legacy rows are excluded from both.

**Isolation: one `@Transactional(readOnly = true, isolation = REPEATABLE_READ)`
transaction** for the whole walk. The walk is several statements across pages, and
a stable snapshot is what makes the answer mean anything: under `READ COMMITTED`
the verifier could read page 1 before a tamper and page 40 after it and report a
discontinuity that never existed at a single instant. `REPEATABLE READ` costs
nothing for a read-only transaction this size and turns the response into "the
chain as of the snapshot". A concurrent append is simply not in the snapshot — an
appending writer cannot make the answer `BROKEN`.

**Cost.** 51,042 events (`README.md:213`) at a ~1µs SHA-256 each is ~50ms of
hashing; the read dominates, at a few hundred milliseconds over the wire. It is
therefore a deliberate request, not a poll (§7) — the same reason
`POST /api/admin/projections/rebuild` is a button and not a timer.

**How it is tested.** `ledger-service` has no database test harness — Postgres and
`spring-boot-starter-test` only (`ledger-service/pom.xml:55,74`); no H2 and no
Testcontainers, the same limit `specs/003-outbox.md` §3.5 records. So
`HashChainService` is written against a row source it can be handed, and the unit
tests drive it with fabricated pages: an intact chain, an edited payload, a
rewritten digest caught by the link, a null hole, a second `GENESIS`, and a legacy
prefix. The database-level assertions are the manual checks in §8 and AC-1…AC-6.

### 5. `GET /api/admin/audit/verify`

Mapped under `/api/admin/audit`, the root the other admin controllers use
(`ProjectionAdminController.java:36`, `OutboxAdminController.java:47`). Through
the gateway and the frontend proxy the browser calls
**`/ledger-service/api/admin/audit/verify`**; the Angular service already anchors
on `/ledger-service/api` (`ledger.service.ts:10`).

| Field | Type | Present | Meaning |
|---|---|---|---|
| `status` | string | always | exactly `"INTACT"` or `"BROKEN"` |
| `eventsChecked` | int | always | chain events verified, including the failing row on `BROKEN` |
| `brokenAtEventId` | string | **only when `BROKEN`** | the offending row's `event_store.id`, as a decimal string |

```json
{ "status": "INTACT", "eventsChecked": 42 }
```

```json
{ "status": "BROKEN", "eventsChecked": 12, "brokenAtEventId": "51044" }
```

Four decisions the contract depends on:

- **HTTP 200 in both cases.** The *verification* succeeded; the ledger is what
  failed. A 5xx would tell the caller the audit is broken, which is the opposite
  of the message and would make the UI's red card indistinguishable from an
  outage.
- **`brokenAtEventId` is the `event_store.id`, not the payload's `eventId`.**
  The vocabulary clash is real: in this codebase `Event#getEventId()` is the
  domain UUID inside the payload (`Event.java:35-37`), while the row's log
  position is `EventEntity.id` (`EventEntity.java:43-45`), which the store calls
  the event's *offset* (`Event.java:62-68`). The field name is fixed by the brief,
  so it is defined here as the log position: it is `NOT NULL`, it is the walk's
  ordering key, and it is the value an operator types into `WHERE id = …` — the
  three properties the UUID lacks when the payload itself is what was tampered.
  The UI labels it `event #<id>` to avoid implying the UUID.
- **The key is absent on `INTACT`, not null.** `@JsonInclude(NON_NULL)` on the
  response record, following the argument `specs/003-outbox.md` §6 records for
  `payload`: a field that is never read should not be in the body. The UI treats
  absent and null identically.
- **`GET`, not `POST`.** Unlike the projection rebuild, this reads committed state
  and has no side effects; it is a verification *query*. It is still expensive
  (a full scan), so the response carries **`Cache-Control: no-store`** — a cached
  verification answer is a false statement about a chain that may have changed.

An empty or legacy-only table returns `{"status":"INTACT","eventsChecked":0}`.
That is honest: nothing in the chain has been contradicted. The page says so in
words (§7) rather than showing a green card over 0 events and letting an operator
infer coverage it does not have.

### 6. Authorization

**`MANAGER` is required**, and the mechanism is the one this service has:
`ledger-service` has no Spring Security on purpose (`HeaderAuthFilter.java:22-24`;
`AGENTS.md:26` forbids adding it), so there is no `@PreAuthorize` to attach. The
controller asserts `callerContext.hasRole("MANAGER")`
(`CallerContext.java:31-33`) before any work and throws `ForbiddenException`,
which `ApiExceptionHandler.java:42-45` maps to **403** — exactly
`OutboxAdminController.java:117-121`, whose `MANAGER_ROLE` constant
(`:51-52`) records that the realm has no `ADMIN` role
(`keycloak/realm-export.json` defines `TELLER`, `MANAGER`, `AUDITOR`).

Both layers hold, with **no gateway change**: the edge rule
(`gateway .../SecurityConfig.java:78`) already covers `/ledger-service/api/admin/**`,
and the service check is what refuses a direct call to port 8085 that arrives
with no identity headers at all (`HeaderAuthFilter.java:26-29` leaves the context
empty, so the role check fails closed).

### 7. UI — Observability → Chain Audit

**Route.** `{ path: 'observability/audit', component: AuditComponent,
canActivate: [roleGuard(['MANAGER'])] }` as a child of the guarded `''` route in
`app.routes.ts:39-74`, after `observability/outbox` (`:69`). The children inherit
`authGuard` from the parent (`:41`), so only the role guard is added — the shape
`backoffice/users` uses (`:72`). Guarded because the API is: the guard is UX, the
gateway and the service enforce.

**Component.** `ecom-frontend/src/app/components/observability/audit/` —
`audit.component.ts`, `.html`, `.scss` (+ `.spec.ts`), standalone with signals,
modelled on the outbox page (`outbox.component.ts`): `inject()`, `signal()` for
state, `takeUntilDestroyed`, PrimeNG `CardModule` / `ButtonModule` /
`TagModule` / `TooltipModule`, and the `gateError` + `describeFailure(err)`
pattern (`outbox.component.ts:66`, `:189-204`) that turns 404 and 403 into
sentences instead of an empty page.

**API call.** One method on the existing `LedgerService`
(`ledger.service.ts:10`): `verifyChain(): Observable<AuditVerification>` →
`GET ${base}/admin/audit/verify`, marked `silentWhen(true)`
(`http-context.tokens.ts`) like `getOutboxStatus()` (`ledger.service.ts:172-175`),
so the failure renders in the card area instead of as a global toast.

**The two hero states, which are the whole page:**

| State | Card | Copy |
|---|---|---|
| `INTACT` | green (`success`), `pi pi-verified` | **CHAIN VERIFIED** — "{eventsChecked} events checked · verified {time}" — button **Verify again** |
| `BROKEN` | red (`danger`), `pi pi-exclamation-triangle` | **TAMPER DETECTED** — "The chain breaks at event #{brokenAtEventId} — the {eventsChecked}th event checked. An event's digest no longer matches its contents, or a later event no longer links to it." — button **Verify again** |

- **Colour is never the only signal.** The icon and the literal words carry the
  state as well, and the card is `role="status"` with `aria-live="polite"` so a
  screen reader announces the result. This is the same rule
  `outbox.component.ts:140-152` records for its status badges.
- **No auto-refresh.** The outbox polls every 5s (`outbox.component.ts:54-55`)
  because it reads a counter; this walks the whole table. It verifies once on
  load and again only on the button. A five-second full scan of a 51k-row log
  would be a denial of service an operator inflicts on their own ledger.
- **States beyond the two cards:** initial load → indeterminate spinner and
  "Verifying the chain…" (no percentage: there is a single response); `404` →
  "Chain audit is disabled." as an informational card, not an error, because the
  gate is a configuration (`application.properties`); `403` → "You do not hold
  MANAGER role."; network/other → the message in the card area, with **Verify
  again** still enabled; `eventsChecked = 0` → "No events in the chain yet.
  Events written before the chain was enabled are outside it and are not
  counted."
- **A footnote in the page, not the docs:** "The chain covers every event
  appended since the hash chain was enabled (migration V5). Earlier events are
  outside it." — the §2.4 limitation where the operator is standing.

**Nav.** A fourth item in the existing **Observability** group
(`app.component.ts:80-90`), after "Outbox":

```ts
{ label: 'Chain Audit', icon: 'pi pi-verified', route: '/observability/audit', roles: ['MANAGER'] }
```

The group, the `roles` field (`:88`) and `hasRequiredRole` (`:102-106`) already
exist, so the item is hidden from non-managers exactly like "Outbox".

### 8. Manual verification script

Prerequisites: ledger-service running against Postgres on 5433
(`application.properties:125`), started with `--ledger.audit.enabled=true`.
`psql` as the `ledger` user.

```sql
-- 1. The migration landed and the columns are as specified.
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
\d event_store

-- 2. The head of the chain: the newest rows, linked.
SELECT id, previous_hash, hash FROM event_store WHERE hash IS NOT NULL ORDER BY id DESC LIMIT 3;

-- 3. Independent recomputation, outside the application. Needs pgcrypto in the
--    session's database (CREATE EXTENSION pgcrypto as a superuser). This is a
--    manual check only — the migration deliberately does not depend on it.
SELECT id, hash = encode(digest(previous_hash || payload, 'sha256'), 'hex') AS ok
FROM event_store WHERE hash IS NOT NULL ORDER BY id LIMIT 5;

-- 4. Tamper with one event, keeping valid JSON and keeping the digest stale.
CREATE TEMP TABLE backup AS SELECT * FROM event_store WHERE id = <ID>;
UPDATE event_store
   SET payload = (payload::jsonb || '{"amount": 999999.0}'::jsonb)::text
 WHERE id = <ID>;
-- 5. Now call the API: GET /ledger-service/api/admin/audit/verify
-- 6. Restore from the backup (jsonb reorders keys, so re-reading the row is not
--    enough — the bytes must be put back).
UPDATE event_store e SET payload = b.payload FROM backup b WHERE e.id = b.id;
```

Step 4 changes the *bytes* of a valid payload, which is the point: the verifier
hashes stored bytes and never parses them, so any edit is caught, including one
that a JSON parser would call equivalent.

## Acceptance criteria

- [ ] **AC-1 — schema.** `flyway_schema_history` carries `V5` with `success =
      true`; `\d event_store` shows `hash`/`previous_hash` as `character
      varying(64)`, the three `CHECK` constraints and
      `uq_event_store_previous_hash`; the rows written before the migration have
      both columns `NULL` (never one of the two); `mvn test` passes.
- [ ] **AC-2 — append hashes.** With an empty chain, the first append produces a
      row with `previous_hash = 'GENESIS'` and a 64-character lowercase hex
      `hash`; a second row's `previous_hash` equals the first row's `hash`; a
      transfer appends two events and they link to each other. In SQL, step 3 of
      §8 reports `ok = true` for every hashed row, and the value is
      independently reproducible with `pgcrypto` — i.e. the Java hashing rule is
      confirmed by an implementation that shares no code with it.
- [ ] **AC-3 — verify reports INTACT.** `GET /api/admin/audit/verify` returns
      `200` with `status: "INTACT"`, no `brokenAtEventId` key, and
      `eventsChecked` equal to `SELECT count(*) FROM event_store WHERE hash IS
      NOT NULL`. On a wiped database reseeded through the seeder that count equals
      the total event count, because the seeder appends through `EventStore`
      (§2.4).
- [ ] **AC-4 — an edited payload is caught.** After §8 step 4 on any hashed row,
      the API returns `status: "BROKEN"` with `brokenAtEventId` equal to that
      row's `id` as a string, and `eventsChecked` equal to its 1-based position in
      the chain. Restoring the row from the backup returns the answer to
      `INTACT`, so the check is reproducible rather than sticky.
- [ ] **AC-5 — a rewritten digest is caught by the link.** After §8 step 4 on a
      row that has a successor, also recompute that row's `hash` in SQL
      (`encode(digest(previous_hash || payload, 'sha256'), 'hex')`) so the row is
      internally consistent. The API must still report `BROKEN`, now at the *next*
      event id, because its `previous_hash` no longer matches. This is the clause
      that proves the feature is a chain and not 51,042 independent checksums.
      Restore both rows afterwards.
- [ ] **AC-6 — no forks under concurrency.** With 10 transfers issued in parallel
      against a chain that exists,
      `SELECT previous_hash, count(*) FROM event_store WHERE hash IS NOT NULL
      GROUP BY 1 HAVING count(*) > 1` returns **no rows**, and the API still
      reports `INTACT`. Repeat twice; a fork shows as a `duplicate key` violation
      on `uq_event_store_previous_hash`, which is the assertion working (§3.3).
- [ ] **AC-7 — gate and authorization.** With `ledger.audit.enabled` absent or
      `false` the endpoint is **404** (no handler mapping) and the page shows
      "Chain audit is disabled."; with it `true`, a caller without `MANAGER` gets
      **403** from the service, a direct call to port 8085 with no identity
      headers gets **403** (empty `CallerContext`), and an unauthenticated call
      through the gateway gets **401** (`.anyExchange().authenticated()`), and a
      teller gets **403** from the edge (`SecurityConfig.java:78`).
- [ ] **AC-8 — the UI tells the truth.** On a healthy chain the page renders the
      green **CHAIN VERIFIED** card with the event count; after a tamper it
      renders the red **TAMPER DETECTED** card naming the event id; the
      `eventsChecked = 0` state says no events are in the chain rather than
      claiming coverage; the page does **not** re-verify on a timer (watch the
      network log for 30s after load); the Karma spec covers INTACT, BROKEN, 404
      and 403, following the outbox spec precedent.
- [ ] **AC-9 — no regressions.** `mvn test` passes, including
      `TransferSagaServiceFundsCheckTest` and `TransferSagaArchiveTest`, which
      stub `EventStore` and must not be moved into a Spring context; the `inmem`
      profile still boots with no audit bean;
      a transfer with Kafka down still commits and still reverses when the archive
      fails (spec 003 AC-9), because nothing in this feature touches the archive
      transaction; and the existing projection rebuild still reports zero
      mismatches on the seeded ledger.

## Constraints

- **Flyway owns the schema** (`AGENTS.md:31`, `application.properties:129`
  `ddl-auto=validate`). One additive migration, `V5`; no existing column, index or
  constraint is altered. `V5` was confirmed free (max is `V4`).
- **No new dependency, therefore no ADR.** SHA-256 is
  `java.security.MessageDigest` and lowercase hex is `java.util.HexFormat`
  (Java 21, `ledger-service/pom.xml:17`). No Guava, no commons-codec.
- **One hashing implementation.** Writer and verifier call the same
  `EventHasher.sha256Hex`. A second implementation (in a migration, a test
  fixture or a report script) is how this feature would report `BROKEN` on a
  healthy log — the worst failure it can have, because it destroys trust in the
  check itself.
- **No SQL backfill and no `pgcrypto` in a migration** (§2.4). `pgcrypto` appears
  only in the manual AC procedure, in the operator's own session.
- **No Spring Security**, no `spring-boot-starter-security`
  (`HeaderAuthFilter.java:22-24`, `AGENTS.md:26`). `MANAGER` is checked through
  `CallerContext`.
- **No `docker-compose.yml` change** (`AGENTS.md:24`). Postgres is not in the
  compose file at all (`ARCHITECTURE.md:111`).
- **No scheduled verification and no startup failure.** A periodic full scan is
  not added; a chain broken at boot does not stop the service (§Out of scope).
  The precedent in this repository points the other way —
  `LedgerIntegrityChecker` refuses to start on an unbalanced journal — and is
  deliberately not followed: a tamper finding must not be able to take the ledger
  offline, or the first thing an attacker gains is an outage.
- **The chain is never written to by the verifier.** `HashChainService` performs
  only reads; the §8 procedure is a *manual* test and its restores are the
  operator's business, not a feature.
- **The event store stays append-only.** This feature adds two columns to new
  rows. No repair, rewrite or normalization path is introduced; there is no
  "fix the chain" endpoint, and there must not be one.

## Out of scope

- **Backfilling the 51,042 pre-existing rows, and `V6 … SET NOT NULL`.** §2.4
  explains why a fabricated digest is worse than an honest gap, and how a reseed
  gets full coverage today.
- **An external anchor.** An in-database chain cannot detect two attacks: a
  deleted *tail* (remove the newest rows and there is no successor to disagree),
  and a *full rewrite* by an administrator who recomputes every digest from the
  tampered row forward. Both need a digest recorded outside the database — a
  signed head committed daily, a write-once object store, an external audit log.
  That is a separate milestone with its own key-management questions, and it is
  the next honest increment; this spec states the limit rather than implying the
  chain is unforgeable.
- **Columns the digest does not cover.** The hash input is
  `previous_hash + payload` (the brief). `occurred_at`, `aggregate_id` and
  `event_type` are *duplicated inside* the payload, so the hash covers their
  values — but an administrator who edits a column while leaving the payload
  intact produces a row that is internally inconsistent and still hash-valid.
  Closing that needs a verifier change (cross-check the three columns against the
  decoded payload) and it is small; it is deliberately not folded in here because
  it would make `BROKEN` mean two different things without a reason field.
- **Verification history, metrics and alerting.** No `audit_runs` table, no
  Micrometer counter, no alert on `BROKEN`. The endpoint is the surface; an
  operator calls it and reads the card.
- **A startup integrity check.** See Constraints; the gated endpoint is the
  deliberate alternative.
- **Covering `journal_entries`, `saga_states` or `outbox_events`.** The event log
  is the authority and the other tables are derived views of it
  (`ARCHITECTURE.md`); chaining a derived table protects a copy, not the ledger.
- **`AUDITOR`-only access, and a role split between "may verify" and "may
  administer".** The realm has an `AUDITOR` role, and an audit page is its
  natural home, but `MANAGER` is the role this deployment already gates
  `/api/admin/**` on. Changing the role model is a Keycloak change, not a
  ledger change.
