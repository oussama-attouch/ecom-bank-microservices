# Feature: ledger-events.DLT consumer (poison-message persistence)

## Business context

**Module:** `billing-service`, Kafka consumer side only. No business API change.
**Role:** bank operations engineer, not an end user. Nothing here is reachable
from the Angular frontend.
**Problem:** the service can prove what it archived; it cannot prove what it
lost.

The consumer path today (verified at write time):

1. `TransactionEventConsumer.onEvent` (`TransactionEventConsumer.java:37`)
   listens on `ledger-events`, parses the payload, inserts into
   `archived_transactions`, then acknowledges manually.
2. Any exception is rethrown (`TransactionEventConsumer.java:68-69`), so
   `DefaultErrorHandler` retries **3 times, 1 s apart**
   (`FixedBackOff(1000L, 3L)`, `KafkaConsumerConfig.java:81`).
3. When the budget is exhausted, `DeadLetterPublishingRecoverer` republishes the
   record to **`<original-topic>.DLT`** on the same partition
   (`record.topic() + ".DLT"`, `KafkaConsumerConfig.java:80`), i.e.
   `ledger-events.DLT`, carrying the `kafka_dlt-*` headers.

Step 3 is where the story stops. `ledger-events.DLT` has **no consumer**: the
poison record sits on the broker, durable but invisible. An operator asked
"did we drop anything yesterday, and what was in it?" has no query to run, no
row to read, and no evidence outside Kafdrop. The auto-create Kafka setting
means the topic also exists whether or not anyone is listening, so its mere
presence is not a signal either.

This feature closes the loop: consume `ledger-events.DLT` and persist each
poison message so a failure becomes an inspectable row instead of a topic
name. It is the "what failed" half of the observability story, opposite
`archived_transactions` which answers "what succeeded".

## Services impacted

- **`billing-service`** — new listener, new container factory, new entity and
  repository.
- Not impacted: `gateway-service`, `ledger-service`, `ecom-frontend`,
  `config-service`, `discovery-service`. No contract change, no new endpoint.

## Expected behavior

### 1. A listener on `ledger-events.DLT`

- New `@KafkaListener(topics = "ledger-events.DLT", containerFactory = "dltListenerContainerFactory")`.
- Signature consumes `ConsumerRecord<String, String>` rather than a bare
  payload, because the `kafka_dlt-*` headers are the only place the original
  coordinates survive: `kafka_dlt-original-topic`,
  `kafka_dlt-original-partition`, `kafka_dlt-original-offset`,
  `kafka_dlt-exception-fqcn`, `kafka_dlt-exception-message`,
  `kafka_dlt-exception-stacktrace`.
- Header names are matched by suffix (`…original-topic`), not by full literal,
  since the prefix differs between `DeadLetterPublishingRecoverer` versions.
- A record whose headers are missing or unparseable is still persisted with
  null original coordinates; data loss is worse than an incomplete row.

### 2. A dedicated `dltListenerContainerFactory`

The main factory cannot be reused: its `ConsumerFactory` pins the group id
through `ConsumerConfig.GROUP_ID_CONFIG` (`KafkaConsumerConfig.java:40`, value
`billing-service`). The DLT factory therefore builds:

- Its **own** `ConsumerFactory` — same bootstrap servers, same `String`
  key/value deserializers, `AUTO_OFFSET_RESET=earliest`, auto-commit off, but
  group `billing-service-dlt`.
- `AckMode.MANUAL`, matching the main listener's contract.
- `setCommonErrorHandler(...)` to a handler with **no recoverer at all**.

### 3. Non-recursive error handling (no DLT ladder)

Two independent guards, because either one alone is easy to regress:

1. **No recoverer.** The DLT handler is a plain
   `new DefaultErrorHandler(new FixedBackOff(0L, 0L))` constructed *without* a
   `DeadLetterPublishingRecoverer`. With no recoverer there is no code path
   that can publish anywhere, so a suffix cannot be appended to the topic name
   even if the retry policy changes later. This is the structural guarantee.
2. **Zero retries.** `FixedBackOff(0L, 0L)` — one attempt, immediate stop
   ("STOP" semantics). `FixedBackOff.UNLIMITED_ATTEMPTS` must never be used
   here.

Consequence: a record that fails to persist is logged at ERROR and skipped.
It is **not** republished, so no `ledger-events.DLT.DLT` topic is ever
created. The original record remains on `ledger-events.DLT` as the durable
source, so nothing is unrecoverable.

### 4. Distinct consumer group

Group id **`billing-service-dlt`**, not the default `billing-service`
(`application.properties:7`). Three reasons:

- The DLT topic's offsets must not be mixed into the main consumer group's
  commit history — a DLT pause must not stall archive consumption.
- DLT lag becomes separately observable per group in Kafdrop / `kafka-consumer-groups`.
- The consumer can be scaled or stopped independently of the archive path.

The DLT listener must **not** inherit `spring.kafka.consumer.group-id`; the
group is set on the DLT consumer factory's own property map so the main
consumer keeps `billing-service`.

### 5. Persistence to `dead_letter_transactions` (H2)

New `DeadLetterTransaction` entity (table `dead_letter_transactions`, explicit
`@Table(name = ...)` in the style of `ArchivedTransaction.java:19`) plus a
`DeadLetterTransactionRepository`, with `@RepositoryRestResource(exported = false)`
mirroring `ArchivedTransactionRepository.java:11` — poison payloads must never
be exposed over Spring Data REST.

| Column | Type | Source |
|---|---|---|
| `id` | `Long`, `IDENTITY` | generated |
| `topic` | `String` | `kafka_dlt-original-topic`, fallback `ledger-events` |
| `partition` | `Integer` | `kafka_dlt-original-partition` |
| `offset` | `Long` | `kafka_dlt-original-offset` |
| `recordKey` | `String` | the record's key, may be null |
| `payload` | `String`, `@Lob` | the raw value, **verbatim** — poison content is the evidence |
| `exceptionFqcn` | `String` | `kafka_dlt-exception-fqcn` |
| `exceptionMessage` | `String`, `@Lob` | `kafka_dlt-exception-message` |
| `failedAt` | `String` | ISO-8601, `Instant.now().toString()` |

`failedAt` is a `String`, not a `timestamp`, deliberately: the codebase already
stores ledger timestamps as ISO-8601 strings (`ArchivedTransaction.java:37`) and
JSON-serialising `Instant` on the wire would introduce a format split.

Schema creation is Hibernate DDL, like every other table in this H2 service
(`config-repo/billing-service.properties:1` defines only the in-memory URL, and
Spring Boot's embedded-database default creates the schema from entities). This
is **not** a violation of the Flyway rule in `AGENTS.md`: Flyway owns
`ledger-service`'s Postgres schema; `billing-service` is the H2 CRUD demo.

Persist, then `ack.acknowledge()`. A failed insert must not acknowledge.

### 6. Isolation level

The DLT consumer uses `read_committed` for consistency with the main consumer
(`KafkaConsumerConfig.java:44`). This is safe and not a behaviour risk: the
`KafkaTemplate` used by `DeadLetterPublishingRecoverer` has no transactional id
(`producerFactory()` at `KafkaConsumerConfig.java:63-69`), so DLT records are
non-transactional and therefore visible to a `read_committed` consumer
immediately.

## Acceptance criteria

- [ ] A deliberately malformed record published to `ledger-events` is retried
      3 times by the main listener, 1 s apart, and then appears on
      `ledger-events.DLT` with `kafka_dlt-*` headers attached.
- [ ] The new DLT listener consumes that record and writes exactly one row to
      `dead_letter_transactions` with `topic = ledger-events`, the original
      partition and offset, the raw payload verbatim, and a non-empty
      `exception_message`.
- [ ] `kafka-topics --list` (or Kafdrop) shows **no** `ledger-events.DLT.DLT`
      topic after the failure.
- [ ] The DLT consumer's group appears as `billing-service-dlt` in
      `kafka-consumer-groups --list`; the main listener still commits under
      `billing-service`.
- [ ] Publishing the same poison record twice yields two rows (a DLT record is
      an occurrence, not an idempotent entity) — and this is a deliberate,
      documented choice, not an accident.
- [ ] If the insert itself fails (e.g. oversized payload), the listener does not
      republish, does not loop, and logs one ERROR line per record.
- [ ] Happy path unchanged: a valid record still lands in
      `archived_transactions` and is acknowledged exactly once.
- [ ] `mvn test` passes, and the existing `*ApplicationTests` context-load test
      still starts with the new beans present.

## Constraints

- **Standard library only.** Spring Kafka and Spring Data JPA are already on
  the classpath; no new dependency and therefore no ADR (per `AGENTS.md`: no
  new dependencies without an ADR).
- **No `NewTopic` beans.** `ledger-events.DLT` is produced by the broker's
  auto-create (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: "true"`, `docker-compose.yml:30`).
  There is no `NewTopic` bean anywhere in the repository today and this feature
  must not add the first one.
- **Partition alignment is already correct, keep it that way.**
  `DeadLetterPublishingRecoverer` publishes to `record.partition()` on the DLT
  topic, so if `ledger-events.DLT` had fewer partitions than `ledger-events`
  the send would fail. Both are auto-created against `KAFKA_NUM_PARTITIONS: 3`
  (`docker-compose.yml:29`), so they match today. Any future explicit
  partition count on `ledger-events` must be mirrored on the DLT topic — an
  operator note, not a code change.
- **`docker-compose.yml` is not touched** (`AGENTS.md`: only inside a milestone
  that names it).
- **Additive configuration.** The implementation may live in a new
  `DltKafkaConsumerConfig` class rather than editing `KafkaConsumerConfig`, so
  the main listener's factory, error handler and recoverer are untouched. If
  `KafkaConsumerConfig` is edited, existing bean behaviour must not change.
- **No new Spring Boot starter**, in particular not
  `spring-boot-starter-security`.

## Out of scope

- **Alerting.** No email, webhook, PagerDuty or Alertmanager on DLT depth or
  age. A row appearing in the table is the signal; wiring a pager is a
  separate spec.
- **Replay / redrive.** No tool to re-publish DLT rows back into
  `ledger-events` after a fix. The rows are evidence, not a queue to drain.
- **Frontend.** No Angular view, no REST endpoint, no Kafdrop replacement. The
  repository stays `exported = false`.
- **Retention and cleanup.** No scheduled purge of `dead_letter_transactions`;
  the H2 database is in-memory and dies with the process
  (`jdbc:h2:mem:bills-db`), so retention is a non-problem for now and a real
  problem only when this moves to a durable store.
- **Changing the main listener.** Its retry budget (3 × 1 s), its DLT topic
  resolution (`<topic>.DLT`) and its idempotency set are unchanged.
- **`ledger-service` changes.** No new event type, no outbox, no change to what
  gets published to `ledger-events`.
- **Exactly-once delivery.** The DLT path stays at-least-once; deduplication of
  DLT rows is explicitly not attempted.
