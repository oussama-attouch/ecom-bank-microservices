# E-Com Bank — Architecture

> Generated from source at commit `7381cb4` (HEAD, working tree clean).
> Every node, port, topic and field name below is taken from the repository. Anything
> that could not be verified in source is marked **NOT FOUND** rather than guessed.

---

## 1. Executive Summary

- **What it is.** E-Com Bank is a banking-grade platform: an **Angular 19** front end (PrimeNG "Aura" theme) over **8 Spring Boot services** — `gateway-service`, `discovery-service`, `config-service`, `customer-service`, `inventory-service`, `billing-service`, `order-service`, `ledger-service` — where `ledger-service` is the real subject: an **event-sourced, CQRS double-entry ledger** with a **transfer saga** and a "Command Center" BI dashboard.

- **Architectural style.** Service-per-bounded-context behind a single API gateway, with two *different* consistency models deliberately mixed: the e-commerce side (`customer`/`inventory`/`order`/`billing`) is ordinary CRUD on H2 + Spring Data REST, while the money side (`ledger-service`) is **event sourcing + CQRS + orchestrated saga** on Postgres with Kafka fan-out to `billing-service`.

- **Key guarantees.** All-or-nothing money movement via compensating events (`SagaStatus.COMPLETED | COMPENSATING | FAILED`); **double-entry bookkeeping** where every posting debits one account and credits another and `verifyTransactionBalanced(transactionId)` asserts total debits == total credits; an append-only `event_store` with `UNIQUE (aggregate_id, sequence_number)`; and an **anti-spoofing identity boundary** — the gateway strips client-supplied `X-User-Id`/`X-User-Roles` before injecting its own from the validated JWT, and fails closed when no principal resolves.

- **Scale.** A portfolio seed mode writes **500 customers / ~1,100 accounts / 40,000 transactions over 24 months**; the KPI/dashboard path was reworked from event replay to SQL aggregates after the seeded ledger made replay the bottleneck (`perf(accounts)`: 10.8s → 200ms; 1,115 queries and ~102,000 JSON decodes per account-list request before).

- **Notable complexity.** Three-layer role enforcement (Angular `roleGuard` → gateway JWT → request-scoped `CallerContext` + `TransferLimitPolicy`); a **$10,000 TELLER transfer limit enforced on every money-out path**, not just the saga; a `TransactionEventPublisher` strategy that swaps HTTP archival for an **idempotent Kafka producer** on one property; graceful-shutdown + port-clearing restart tooling because an orphaned JVM holding 8085 was a recurring failure; and a `SlaMetricsService` built specifically because a Micrometer counter is cumulative-since-JVM-start and cannot answer "the last 24 hours".

---

## 2. System Topology

```mermaid
graph TB
    subgraph clients["Client"]
        FE["ecom-frontend<br/>Angular 19 + PrimeNG<br/>:4200"]
        KC_USER["teller / manager / auditor"]
    end

    subgraph edge["Platform services"]
        GW["gateway-service<br/>Spring Cloud Gateway :8888"]
        DISC["discovery-service<br/>Eureka :8761"]
        CFG["config-service<br/>native profile :9999"]
    end

    subgraph business["Bounded contexts"]
        CUST["customer-service<br/>:8081"]
        INV["inventory-service<br/>:8082"]
        BILL["billing-service<br/>:8083"]
        ORD["order-service<br/>:8084"]
        LEDGER["ledger-service<br/>:8085"]
    end

    subgraph infra["Infrastructure"]
        KAFKA["kafka :9092<br/>zookeeper :2181"]
        KAFDROP["kafdrop :9000"]
        PG["PostgreSQL :5433<br/>ledger_db"]
        H2["H2 in-memory<br/>customers-db / bills-db / ..."]
    end

    subgraph security["Security"]
        KC["keycloak :8180<br/>realm ecom-bank"]
    end

    KC_USER -->|"HTTPS :4200"| FE
    FE -->|"OIDC Authorization Code + PKCE"| KC
    FE -->|"HTTP dev proxy, /service-id/**"| GW
    FE -->|"OIDC roles: realm_access.roles"| KC

    GW -->|"HTTP + JWT bearer, discovery locator route"| CUST
    GW -->|"HTTP + JWT bearer, discovery locator route"| INV
    GW -->|"HTTP + JWT bearer, discovery locator route"| BILL
    GW -->|"HTTP + JWT bearer, discovery locator route"| ORD
    GW -->|"HTTP + JWT bearer, discovery locator route"| LEDGER
    GW -->|"JWKS fetch, issuer-uri validation"| KC
    GW -->|"service registry lookup, every 30s"| DISC

    DISC -->|"registration + heartbeat"| LEDGER
    DISC -->|"registration + heartbeat"| CUST
    DISC -->|"registration + heartbeat"| INV
    DISC -->|"registration + heartbeat"| BILL
    DISC -->|"registration + heartbeat"| ORD

    CFG -->|"optional:configserver, serves config-repo"| CUST
    CFG -->|"optional:configserver, serves config-repo"| INV
    CFG -->|"optional:configserver, serves config-repo"| BILL
    CFG -->|"optional:configserver, serves config-repo"| ORD

    LEDGER -->|"JDBC, event store + journal + sagas"| PG
    CUST -->|"JDBC"| H2
    BILL -->|"JDBC"| H2
    ORD -->|"JDBC"| H2
    INV -->|"JDBC"| H2

    LEDGER -->|"KafkaTemplate.send ledger-events, exactly-once"| KAFKA
    KAFKA -->|"@KafkaListener ledger-events, group billing-service"| BILL
    LEDGER -->|"HTTP POST /api/archived-transactions, ledger.publisher=http"| BILL
    KAFKA -->|"topic browse"| KAFDROP

    LEDGER -->|"Feign customer-service"| CUST
    ORD -->|"Feign customer-service"| CUST
    ORD -->|"Feign inventory-service"| INV
    BILL -->|"Feign customer-service"| CUST
    BILL -->|"Feign inventory-service"| INV

    classDef frontend fill:#1d4ed8,stroke:#0b2a6b,color:#ffffff
    classDef backend fill:#15803d,stroke:#08401f,color:#ffffff
    classDef infrastructure fill:#475569,stroke:#1e293b,color:#ffffff
    classDef sec fill:#b91c1c,stroke:#6b1010,color:#ffffff

    class FE,KC_USER frontend
    class GW,DISC,CFG,CUST,INV,BILL,ORD,LEDGER backend
    class KAFKA,KAFDROP,PG,H2 infrastructure
    class KC sec
```

- **Routing is implicit, not configured.** `gateway-service` contains **no** `RouteLocator`/`routes` bean and no `spring.cloud.gateway.routes` block (**explicit route config: NOT FOUND**). Routing comes entirely from the discovery locator: `spring.cloud.gateway.discovery.locator.lower-case-service-id=true` plus Eureka, so `/{service-id}/**` is forwarded to the matching instance with the prefix stripped. The front end depends on that contract — `proxy.conf.json` maps `/customer-service`, `/inventory-service`, `/billing-service`, `/order-service`, `/ledger-service` all to `http://localhost:8888` with **no** `pathRewrite`.

- **Two persistence tiers.** Only `ledger-service` has a real database: Postgres `jdbc:postgresql://localhost:5433/ledger_db` with `ddl-auto=validate` and Flyway migrations `V1__create_event_store.sql`, `V2__create_journal_and_saga_tables.sql`, `V3__add_seed_indexes.sql`. The other four services run **H2 in-memory** via `config-repo` (`jdbc:h2:mem:customers-db`, `jdbc:h2:mem:bills-db`, `DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`, `maximum-pool-size=20` for pool-churn resilience). **Postgres is not defined in `docker-compose.yml` — NOT FOUND there**; the datasource URL is the only evidence, overridable via `LEDGER_DB_URL`.

- **`config-service` is not a universal dependency.** It serves `file:./config-repo` with the `native` profile on `:9999`, and `customer`, `inventory`, `billing` and `order` import it as `optional:configserver:http://localhost:9999`. **`ledger-service` has no config client at all** — its configuration lives entirely in its own `application.properties`, which is why `gateway-service` also sets `spring.cloud.config.enabled=false`. Because the import is `optional:`, a down config server degrades to local defaults rather than failing startup.

- **Dev-only caveat, recorded in the README.** Services on 8081–8085 are directly reachable in local development, so a local caller can bypass the gateway and forge `X-User-Roles`. The mitigation is deployment topology (private network, gateway as the only exposed edge), not code.

---

## 3. Transfer Saga Sequence

```mermaid
sequenceDiagram
    autonumber
    actor T as Teller
    participant FE as TransferWizardComponent
    participant GW as gateway-service
    participant TC as TransferController
    participant TLP as TransferLimitPolicy
    participant SAGA as TransferSagaService
    participant ES as JpaEventStore
    participant JS as JournalService
    participant PUB as TransactionEventPublisher
    participant BILL as billing-service

    T->>FE: POST /ledger-service/api/transfers amount, source, dest, transactionId
    FE->>GW: HTTP + Authorization Bearer JWT
    GW->>GW: validate JWT against Keycloak JWKS
    GW->>GW: strip X-User-Id, X-User-Roles then re-add from realm_access.roles
    GW->>TC: forward /api/transfers
    TC->>SAGA: execute(TransferRequest, simulateFailure)

    Note over SAGA: preconditions - no saga row is created if any of these throw
    SAGA->>TLP: assertMayMoveFunds(amount)
    TLP-->>SAGA: ForbiddenException 403 if TELLER without MANAGER and amount > 10000
    SAGA->>SAGA: sagaRepository.find(transactionId) idempotency, return existing saga
    SAGA->>SAGA: projection.exists(source), projection.exists(dest), amount > 0, source != dest
    SAGA->>SAGA: projection.balance(source) >= amount else InsufficientFundsException
    SAGA->>SAGA: new SagaTimeline(occurredAt) then sagaRepository.save(saga)
    SAGA->>SAGA: addStep VALIDATE EXECUTED

    rect rgb(219, 234, 254)
    Note over SAGA,BILL: happy path
    SAGA->>ES: append MoneyDebitedEvent txId, source, amount
    ES-->>SAGA: offset id
    SAGA->>JS: postTransferDebit txId, source, amount
    JS->>JS: post source debit, TRANSFER_CLEARING credit
    SAGA->>SAGA: addStep DEBIT_SOURCE EXECUTED

    SAGA->>ES: append MoneyCreditedEvent txId, dest, amount
    ES-->>SAGA: offset id
    SAGA->>JS: postTransferCredit txId, dest, amount
    SAGA->>JS: verifyTransactionBalanced(txId)
    JS-->>SAGA: total debits == total credits
    SAGA->>SAGA: addStep CREDIT_DESTINATION EXECUTED

    alt simulateFailure=true
        SAGA->>SAGA: throw RuntimeException Simulated archive failure demo mode
    else publish=true
        SAGA->>PUB: publishStrict(TransactionRecord)
        PUB->>BILL: ledger.publisher=http POST /api/archived-transactions
        Note over PUB,BILL: or ledger.publisher=kafka KafkaTemplate.send ledger-events keyed by transactionId
    else publish=false - the seeder reconstructing history
        SAGA->>SAGA: record the step only, skip the downstream notification
    end
    SAGA->>SAGA: addStep ARCHIVE EXECUTED
    SAGA->>SAGA: finish COMPLETED
    end

    rect rgb(254, 226, 226)
    Note over SAGA,BILL: COMPENSATING path - ARCHIVE fails after credit succeeded
    SAGA->>SAGA: addStep ARCHIVE FAILED
    Note over SAGA: undo the credit first then the debit - the credit only existed because the debit did
    SAGA->>ES: append MoneyDebitedEvent description SAGA COMPENSATION reverse credit for dest
    SAGA->>JS: reverseTransferCredit txId, dest, amount
    SAGA->>SAGA: addStep COMPENSATE_DEBIT_dest COMPENSATED
    SAGA->>ES: append MoneyCreditedEvent description SAGA COMPENSATION reverse debit for source
    SAGA->>JS: reverseTransferDebit txId, source, amount
    SAGA->>SAGA: addStep COMPENSATE_CREDIT_source COMPENSATED
    alt both reversals succeeded
        SAGA->>SAGA: finish COMPENSATING
    else either reversal failed
        SAGA->>SAGA: finish FAILED - errorMessage Archive failed and compensation failed
        Note over SAGA: logged as SAGA txId compensation failed - CRITICAL
    end
    end

    rect rgb(254, 249, 195)
    Note over SAGA,JS: COMPENSATING path - CREDIT_DESTINATION itself fails
    SAGA->>SAGA: addStep CREDIT_DESTINATION FAILED
    SAGA->>ES: append MoneyCreditedEvent description SAGA COMPENSATION reverse debit for source
    SAGA->>JS: reverseTransferDebit txId, source, amount
    SAGA->>SAGA: addStep COMPENSATE_CREDIT_source COMPENSATED
    SAGA->>SAGA: finish COMPENSATING - the debit was reversed
    end

    SAGA->>SAGA: persist(saga) - sagaRepository.save writes steps and terminal status
    SAGA-->>TC: SagaState
    TC-->>FE: 200 COMPLETED / COMPENSATING / FAILED
    FE->>FE: poll stops on terminal status, 60s timeout
    FE-->>T: render saga timeline
```

- **The four canonical steps are literal strings.** `TransferSagaService` calls `saga.addStep("VALIDATE", …)`, `"DEBIT_SOURCE"`, `"CREDIT_DESTINATION"`, `"ARCHIVE"` with status `EXECUTED`, and records `FAILED` on the step that broke. Compensation steps are **dynamic** — `"COMPENSATE_CREDIT_" + accountId` (a `MoneyCreditedEvent` reversing a debit) and `"COMPENSATE_DEBIT_" + accountId` (a `MoneyDebitedEvent` reversing a credit) — and are recorded as `COMPENSATED`.

- **Compensation is ordered, and the order is deliberate.** On an ARCHIVE failure the service reverses the **credit first, then the debit**, because the credit only existed because the debit did; unwinding it first leaves the source-restoring step last. Both reversals run **even if the first fails**, so a half-unwound transfer is recorded as `FAILED` with `errorMessage` rather than silently left mid-flight.

- **Everything before `VALIDATE` leaves no trace.** The role check and all five validation rules run *before* `sagaRepository.save`, so a rejected transfer creates **no saga row, no event and no journal entry** — and the same `TransferLimitPolicy` guards the direct posting paths (`POST /api/transactions` with `type=TRANSFER|DEBIT`), because a limit enforced on one door is not a limit.

- **Idempotency is the saga's own store.** A repeat `transactionId` returns the existing `SagaState` unchanged; `SagaStatus` has exactly three values — `COMPLETED`, `COMPENSATING`, `FAILED` — and `SagaStateEntity` keeps `started_at`/`completed_at` plus `error_message VARCHAR(1000)`, with steps loaded `FetchType.LAZY` (eager fetching pulled 11,334 extra rows to render 2,765 summaries).

---

## 4. Event Sourcing + CQRS

```mermaid
graph LR
    subgraph write["Write side - commands"]
        REQ["CreateAccountRequest / TransactionRequest / TransferRequest"]
        CTRL["AccountController / TransactionController / TransferController"]
        AGG["AccountProjection.rebuild folds the stream"]
        SAGA["TransferSagaService<br/>VALIDATE DEBIT_SOURCE CREDIT_DESTINATION ARCHIVE"]
        ACCTSVC["AccountService"]
    end

    subgraph store["Append-only log"]
        ESIF["EventStore interface<br/>append / allEvents / eventsForAccount / count / nextOffset"]
        JPAES["JpaEventStore<br/>@Profile not inmem"]
        INMEM["InMemoryEventStore<br/>@Profile inmem"]
        SER["EventSerializer<br/>Jackson type discriminator"]
        TBL[("event_store<br/>id, aggregate_id, sequence_number<br/>event_type, payload TEXT, occurred_at")]
        EVENTS["AccountCreatedEvent<br/>MoneyCreditedEvent<br/>MoneyDebitedEvent"]
    end

    subgraph journal["Double-entry journal"]
        JS["JournalService"]
        JE[("journal_entries")]
        CHECK["verifyTransactionBalanced<br/>TrialBalance"]
    end

    subgraph outbound["Publication"]
        PUBIF["TransactionEventPublisher"]
        HTTPPUB["HttpTransactionEventPublisher<br/>ledger.publisher=http"]
        KAFKAPUB["KafkaTransactionEventPublisher<br/>ledger.publisher=kafka"]
    end

    subgraph read["Read side - projections"]
        SUMMARY["AccountSummaries / JpaAccountSummaries<br/>one SQL aggregate instead of replay"]
        KPIS["LedgerAggregates / JpaLedgerAggregates"]
        REPLAY["ReplayLedgerAggregates<br/>@Profile inmem"]
        CHARTS["ChartSeriesService"]
        KPI["KpiTrendsService"]
        DASH["DashboardController<br/>Command Center"]
        CONSUMER["billing-service TransactionEventConsumer<br/>@KafkaListener ledger-events"]
        ARCH[("archived_transactions")]
    end

    REQ --> CTRL
    CTRL --> SAGA
    CTRL --> ACCTSVC
    ACCTSVC --> ESIF
    SAGA --> ESIF
    SAGA --> JS
    SAGA --> PUBIF
    ESIF -.-> JPAES
    ESIF -.-> INMEM
    JPAES --> SER
    SER --> EVENTS
    JPAES --> TBL
    INMEM --> EVENTS
    JS --> JE
    JS --> CHECK
    PUBIF -.-> HTTPPUB
    PUBIF -.-> KAFKAPUB
    HTTPPUB --> CONSUMER
    KAFKAPUB --> CONSUMER
    CONSUMER --> ARCH
    TBL --> AGG
    TBL --> SUMMARY
    TBL --> KPIS
    TBL --> REPLAY
    SUMMARY --> AGG
    KPIS --> KPI
    KPIS --> CHARTS
    KPI --> DASH
    CHARTS --> DASH
    JE --> CHECK

    classDef w fill:#1d4ed8,stroke:#0b2a6b,color:#ffffff
    classDef s fill:#15803d,stroke:#08401f,color:#ffffff
    classDef r fill:#7c3aed,stroke:#3b1069,color:#ffffff
    classDef o fill:#b45309,stroke:#5c2a04,color:#ffffff

    class REQ,CTRL,AGG,SAGA,ACCTSVC w
    class ESIF,JPAES,INMEM,SER,EVENTS,TBL,JS,JE,CHECK s
    class SUMMARY,KPIS,REPLAY,CHARTS,KPI,DASH,CONSUMER,ARCH r
    class PUBIF,HTTPPUB,KAFKAPUB o
```

- **There is no account table and no mutable balance.** `AccountProjection` derives balance, holder and history by folding the stream (`AccountCreatedEvent` sets `customerId`/`holderName`, `MoneyCreditedEvent` adds, `MoneyDebitedEvent` subtracts); `AccountState` is the fold result plus the events themselves.

- **Two log positions, on purpose.** The surrogate `id BIGSERIAL` is the **global** position in the log — `JpaEventStore.allEvents()` sorts by it and it is handed back via `Event.setOffset` — while `sequence_number` is the position **within one aggregate**, guarded by `UNIQUE (aggregate_id, sequence_number)`; `append` tracks the next sequence per aggregate in a `HashMap` so a batch of two events for the same account cannot collide.

- **Replay is the correctness path, aggregation is the performance path.** Replay is fine per account (`statement`, `history`, `balance`, and the saga's funds check all use it) but not for the whole ledger: `allAccounts()` was 1,115 queries and ~102,000 JSON decodes per request at portfolio scale. `AccountProjection` therefore delegates to `AccountSummaries` when present — `@Autowired(required = false)`, with replay kept as the `inmem` fallback — and `JpaLedgerAggregates` / `ChartSeriesService` / `KpiTrendsService` answer the dashboard from SQL.

- **The read model that crosses a service boundary is `billing-service`.** `TransactionEventConsumer` consumes `ledger-events` and appends an immutable `ArchivedTransaction` (`transactionId`, `type`, `accountId`, `fromAccountId`, `toAccountId`, `amount`, `timestamp`), deduplicating redeliveries by an in-memory `ConcurrentHashMap` id set — swap for Redis in production, per its own javadoc.

---

## 5. Kafka Topic Topology

```mermaid
graph LR
    subgraph producers["Producers"]
        KP["ledger-service<br/>KafkaTransactionEventPublisher<br/>ledger.publisher=kafka"]
        REC["billing-service<br/>DeadLetterPublishingRecoverer"]
    end

    subgraph topics["Topics"]
        LE["ledger-events<br/>key = transactionId<br/>3 partitions via KAFKA_NUM_PARTITIONS<br/>auto-created"]
        DLT["ledger-events.DLT<br/>topic + .DLT per failing record<br/>same partition number"]
    end

    subgraph consumers["Consumers"]
        TE["billing-service<br/>TransactionEventConsumer<br/>@KafkaListener topics = ledger-events"]
        CG["consumer group<br/>billing-service<br/>spring.kafka.consumer.group-id"]
    end

    subgraph sink["Sink"]
        ARCH[("archived_transactions<br/>H2 bills-db")]
    end

    KP -->|"send topic, key=transactionId, .get 10 SECONDS"| LE
    LE -->|"read_committed, auto-offset-reset earliest"| CG
    CG --> TE
    TE -->|"repository.save, in-memory dedupe by transactionId"| ARCH
    TE -->|"Acknowledgment.acknowledge MANUAL ack after archive"| CG
    TE -.->|"throw RuntimeException after FixedBackOff 1000ms x 3 attempts"| REC
    REC -.->|"new TopicPartition record.topic + .DLT"| DLT

    classDef p fill:#1d4ed8,stroke:#0b2a6b,color:#ffffff
    classDef t fill:#15803d,stroke:#08401f,color:#ffffff
    classDef c fill:#7c3aed,stroke:#3b1069,color:#ffffff
    classDef d fill:#b91c1c,stroke:#6b1010,color:#ffffff

    class KP,REC p
    class LE t
    class DLT d
    class TE,CG c
    class ARCH c
```

| Topic | Producer | Consumer group | Purpose |

|---|---|---|---|
| `ledger-events` | `ledger-service` — `KafkaTransactionEventPublisher`, active only when `ledger.publisher=kafka` | `billing-service` (`spring.kafka.consumer.group-id=billing-service`) | Carry each committed `TransactionRecord` from the ledger to `billing-service`, which archives it into `archived_transactions`. Keyed by `transactionId` so all records for one transaction land on one partition and keep their order. |

| `ledger-events.DLT` | `billing-service` — `DeadLetterPublishingRecoverer` | none (no `@KafkaListener` for it — **DLT consumer: NOT FOUND**) | Dead-letter parking for records that still fail after retries. Destination is computed per record as `record.topic() + ".DLT"` on the **same partition number**. |

- **Retry policy is `FixedBackOff(1000L, 3L)`** — three attempts, one second apart, wired as the container factory's `commonErrorHandler`; only after that does the recoverer publish to the DLT. The listener's own `catch` rethrows a `RuntimeException` specifically so the handler sees a failure instead of the record being acked.

- **Delivery is manual-ack and read-committed.** `ENABLE_AUTO_COMMIT_CONFIG=false` with `AckMode.MANUAL`, and `ISOLATION_LEVEL_CONFIG=read_committed` so the consumer only sees committed (transactional) records; `acknowledge()` is called *after* the archive write, and duplicates short-circuit to an immediate ack.

- **The producer is idempotent, with a bounded retry window.** `ACKS_CONFIG=all`, `ENABLE_IDEMPOTENCE_CONFIG=true`, `MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION=5`, `RETRIES_CONFIG=Integer.MAX_VALUE`, but `REQUEST_TIMEOUT_MS=8000`, `MAX_BLOCK_MS=10000` and `DELIVERY_TIMEOUT_MS=10000` bound total retry time so a Kafka outage **fails fast for the saga to compensate** on the ARCHIVE step instead of hanging it.

- **HTTP is the default transport, Kafka is the opt-in.** `ledger.publisher` defaults to `http` (`HttpTransactionEventPublisher`, `matchIfMissing = true`) which POSTs the same `TransactionRecord` to `billing-service /api/archived-transactions`; both implementations are `@ConditionalOnProperty` so only one bean exists. `AUTO_CREATE_TOPICS_ENABLE: "true"` on the broker means neither `ledger-events` nor `ledger-events.DLT` needs a `NewTopic` bean — and **no `NewTopic`/`TopicBuilder` declaration exists in the repository (NOT FOUND)**.

- **Keying buys ordering and costs parallelism.** Keying by `transactionId` pins every record for one transaction to a single partition, which is what preserves their relative order; with `KAFKA_NUM_PARTITIONS: 3` a single broker caps the read side at three concurrent partitions, and `KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS: 0` shortens the rebalance stall for a single-consumer demo deployment. `ISOLATION_LEVEL_CONFIG=read_committed` means records sit invisible until committed, so the consumer sees either the whole record or nothing.

- **The archive is only as durable as the store under it.** `archived_transactions` lives in `billing-service`'s H2 in-memory `bills-db` (`DB_CLOSE_DELAY=-1`), so the Kafka consumer's at-least-once guarantee and its in-memory dedupe set are both bounded by the JVM — a restart replays from `earliest` against an empty table and re-archives, which is exactly why the producer keys by `transactionId` and the repository save is the idempotency key the javadoc points at Redis to replace.

---

## 6. Authentication & Authorization

```mermaid
sequenceDiagram
    autonumber
    actor U as User
    participant FE as Angular 19 app :4200
    participant AG as angular-auth-oidc-client
    participant KC as keycloak :8180 realm ecom-bank
    participant AI as authInterceptor
    participant RG as roleGuard
    participant GW as gateway-service :8888
    participant LED as ledger-service :8085
    participant HF as HeaderAuthFilter
    participant CC as CallerContext request scope
    participant TLP as TransferLimitPolicy

    U->>FE: open http://localhost:4200/
    FE->>AG: provideAuth authority, clientId ecom-frontend, responseType code
    AG->>KC: OIDC Authorization Code flow, scope openid profile email
    KC-->>U: login page
    U->>KC: teller/teller123 or manager/manager123 or auditor/auditor123
    KC-->>AG: authorization code, then access token + refresh token
    Note over KC: accessTokenLifespan 900s, ssoSessionIdleTimeout 1800s
    AG->>AG: silentRenew true, useRefreshToken true
    AG->>AG: getPayloadFromAccessToken reads realm_access.roles
    Note over AG: realm_access.roles lives in the ACCESS token, not the ID token
    AG-->>FE: userRoles$ BehaviorSubject, isAuthenticated$ sharedReplay

    U->>FE: navigate to /banking/transfer-wizard
    FE->>RG: canActivate roleGuard TELLER MANAGER
    RG->>AG: waitForRoles filter roles.length > 0, take 1
    alt caller holds TELLER or MANAGER
        RG-->>FE: true, route activates
    else caller is AUDITOR only
        RG-->>FE: createUrlTree /dashboard redirect
    end

    FE->>AI: any Http request to /ledger-service/**
    AI->>AG: getAccessToken
    AG-->>AI: Bearer token
    AI->>GW: request + Authorization Bearer
    GW->>KC: fetch JWKS jwk-set-uri protocol/openid-connect/certs
    KC-->>GW: signing keys
    GW->>GW: validate signature, issuer-uri and expiry

    alt token valid
        GW->>GW: strip X-User-Id and X-User-Roles unconditionally
        GW->>GW: read jwt.getSubject and realm_access.roles
        GW->>LED: request with X-User-Id sub, X-User-Roles MANAGER,TELLER
    else token missing or invalid
        GW-->>FE: 401, anyExchange authenticated, only /actuator/** and /eureka/** permitted
    end

    LED->>HF: OncePerRequestFilter at Ordered.LOWEST_PRECEDENCE
    HF->>HF: parse X-User-Roles, split comma, trim, toUpperCase
    HF->>CC: setUserId, setRoles
    Note over HF,CC: both headers absent leaves the context empty rather than rejecting
    LED->>TLP: assertMayMoveFunds(amount) on every money-out path
    alt TELLER without MANAGER and amount > 10000
        TLP-->>LED: ForbiddenException 403, manager approval required
    else MANAGER or AUDITOR or amount <= 10000
        TLP-->>LED: allowed
    end
```

- **Three layers, three different jobs.** The **frontend** `roleGuard(['TELLER','MANAGER'])` on `banking/transfer-wizard` is the only role-gated route in `app.routes.ts` — everything else sits behind `authGuard` alone, and `**` redirects to `/dashboard`. The **gateway** validates the JWT signature against the JWKS and performs identity *translation*. The **backend** (`ledger-service`) makes the actual authorization decision with `TransferLimitPolicy.assertMayMoveFunds`, called first thing in `TransferSagaService.execute`.

- **The header rewrite fails closed, and the comments say why.** The filter *always* removes both headers on the way through, then re-adds them only from the authenticated `JwtAuthenticationToken` read out of the reactive `SecurityContext`; the fallback branch is `defaultIfEmpty(stripIdentityHeaders(exchange))`, i.e. strip-and-continue, never *forward-as-is*. Two silent bugs are documented as fixed: resolving the principal with `exchange.getPrincipal()` (empty at that filter position, so forged headers passed through) and mutating a read-only `HttpHeaders` inside `mutate()` (which threw `UnsupportedOperationException`). The rewrite now uses a `ServerHttpRequestDecorator` that replaces the header map.

- **Roles are read from `realm_access`, and comparison is case-hardened.** The gateway joins `realm_access.roles` into a comma-separated `X-User-Roles`; `HeaderAuthFilter` upper-cases every parsed role so a realm reconfigured with different capitalisation cannot silently stop matching. The limit constant is `TELLER_TRANSFER_LIMIT = BigDecimal("10000")`, compared via `BigDecimal.valueOf(amount)` (shortest decimal representation, not binary floating point) and strictly "more than", so exactly 10,000 is allowed.

- **AUDITOR is a read-only, no-money role.** It holds only `AUDITOR`, so the transfer wizard guard rejects it and the teller-limit rule does not apply to it either (the rule triggers on `TELLER && !MANAGER`); `manager` is deliberately `TELLER + MANAGER` so it clears both the guard and the limit. Note the deliberate asymmetry — **`ledger-service` does not depend on `spring-boot-starter-security` at all**: `HeaderAuthFilter` is a plain servlet filter, and an empty context (direct-port call) simply means no role rules apply rather than a 401.

---

## 7. Data Model

```mermaid
erDiagram
    saga_states ||--o{ saga_steps : "saga_id FK ON DELETE CASCADE"

    saga_states {
        string transaction_id PK
        string status
        string source_account_id
        string destination_account_id
        numeric amount
        string error_message
        timestamp started_at
        timestamp completed_at
    }

    saga_steps {
        bigint id PK
        string saga_id FK
        string name
        string status
        bigint step_offset
        timestamp timestamp
    }

    event_store {
        bigint id PK
        string aggregate_id
        bigint sequence_number
        string event_type
        text payload
        timestamp occurred_at
    }

    journal_entries {
        string id PK
        string transaction_id
        string debit_account_id
        string credit_account_id
        numeric amount
        string currency
        string description
        timestamp created_at
        string posted_by
    }

    archived_transactions {
        bigint id PK
        string transaction_id
        string type
        string account_id
        string from_account_id
        string to_account_id
        double amount
        string timestamp
    }

    customers {
        bigint id PK
        string name
        string email
    }

    products {
        bigint id PK
        string name
        double price
        int quantity
    }

    orders {
        bigint id PK
        date created_at
        string status
        bigint customer_id
    }

    order_product_item {
        bigint id PK
        bigint order_id FK
        bigint product_id
        double price
        int quantity
        double discount
    }

    bills {
        bigint id PK
        date billing_date
        long customer_id
    }

    bill_product_item {
        bigint id PK
        bigint bill_id FK
        string product_id
        int quantity
        double unit_price
    }

    orders ||--o{ order_product_item : "mappedBy order"
    bills ||--o{ bill_product_item : "mappedBy bill"
```

- **`ledger-service` owns three tables in `ledger_db` (Postgres, Flyway V1–V3).** `event_store` is append-only with `UNIQUE (aggregate_id, sequence_number)` and indexes on `aggregate_id`, `event_type`, `occurred_at`; `journal_entries` is keyed by an **application-assigned** `VARCHAR(36)` UUID (the entity implements `Persistable` with `@PostLoad`/`@PostPersist` so a non-null id still inserts rather than merging); `saga_states` is keyed by the business `transaction_id` with `saga_steps` cascading on delete. V3 added `idx_event_store_occurred_at`, `idx_journal_entries_created_at`, `idx_saga_states_status_started`, `idx_journal_entries_debit_created` and `idx_journal_entries_credit_created`.

- **Column-level details the diagram leaves out.** `event_store.id` and `saga_steps.id` are `BIGSERIAL`/`IDENTITY` while `event_store.sequence_number` is unique *per aggregate*, not globally; `journal_entries.amount` and `saga_states.amount` are `NUMERIC(19,4)`, `currency` is `VARCHAR(3)` (always `"USD"`), `description` is `VARCHAR(500)` and `error_message` is `VARCHAR(1000)`; `archived_transactions` uses `IDENTITY` with `amount` as a `double` and `timestamp` as an ISO-8601 **string**, not a temporal column; `saga_steps.step_offset` was renamed from `offset` because that is a PostgreSQL reserved word. `orders.product_items` and `bills.product_items` are `@OneToMany` collections that live in the `order_product_item` / `bill_product_item` tables rather than as columns.

- **Aggregates are accounts, and the account table does not exist.** `aggregate_id` is the account id; account identity comes from `AccountCreatedEvent`'s `accountId` / `customerId` / `holderName`. **Balance is not a column anywhere** — it is the fold of `MONEY_CREDITED` minus `MONEY_DEBITED`.

- **The journal has two pseudo-accounts that are not rows in any table.** `JournalService.CASH_ACCOUNT = "CASH_ACCOUNT"` and `TRANSFER_CLEARING = "TRANSFER_CLEARING"` are string constants written into `debit_account_id`/`credit_account_id`: a plain credit is `CASH_ACCOUNT → account`, a plain debit is `account → CASH_ACCOUNT`, and a transfer is two entries routed through `TRANSFER_CLEARING` (`account → CLEARING`, then `CLEARING → account`). Compensation postings swap the direction of the same two accounts (`reverseTransferDebit` = `CLEARING → account`, `reverseTransferCredit` = `account → CLEARING`).

- **`billing-service` owns the only cross-service read model.** `archived_transactions` mirrors the ledger's `TransactionRecord` record one-for-one and is explicitly immutable ("stored as-is; never mutated"), with `timestamp` kept as a `String` so it round-trips regardless of the consumer's date handling.

- **Cross-service references are plain ids, never JPA relations.** `orders.customer_id → customer-service Customer.id`, `orders.order_product_item.product_id → inventory-service Product.id`, `bills.customer_id → customer-service Customer.id`, `bills.bill_product_item.product_id → inventory-service Product.id`, and `ledger` accounts' `AccountCreatedEvent.customerId → customer-service Customer.id`. Each is resolved at request time by a Feign client (`CustomerRestClientService`, `InventoryRestClientService`, `ledger`/`billing` `CustomerRestClient`, `billing` `ProductRestClient`) into a `@Transient` field, which is why `Customer` and `Product` appear as local model classes in `ledger-service`, `order-service` and `billing-service`. The only real foreign key in the whole system is `saga_steps.saga_id → saga_states.transaction_id`.

---

## 8. Engineering Decisions

| Decision | Alternative | Why this choice |

|---|---|---|
| **Orchestrated saga with compensating events** (`TransferSagaService`, steps `VALIDATE → DEBIT_SOURCE → CREDIT_DESTINATION → ARCHIVE`, statuses `COMPLETED`/`COMPENSATING`/`FAILED`) | Two-phase commit / XA across services | The write path spans an event log, a journal and a downstream publisher that is *sometimes Kafka* — no single transaction manager covers all three, and 2PC would hold locks across a broker call. Compensation is expressed in the domain ("reverse the credit, then the debit") and is itself recorded as `COMPENSATE_CREDIT_*`/`COMPENSATE_DEBIT_*` steps, so a failed unwind is auditable rather than invisible. Compensation running even when the first reversal fails is what makes "half-unwound" a recorded state instead of a lost one. |

| **Event sourcing + CQRS** (`event_store`, `AccountProjection`, no balance column) | CRUD rows with a mutable `balance` column | The ledger is the one place where "how did we get here" matters as much as "what is true now": every state is reconstructible from an append-only log, the log is the audit trail (`aggregate_id` + `sequence_number` ordering), and balances cannot drift from their history because there is no second copy to drift from. `UNIQUE (aggregate_id, sequence_number)` gives optimistic concurrency per account, and the surrogate `id` doubles as a global position. The cost is honest and was measured: replay was 10.8s for the account list, so the read side moved to SQL aggregates while replay stayed the correctness path for single accounts. |

| **Kafka as an opt-in transport** (`ledger.publisher=http` default, `kafka` for exactly-once) | Kafka-only from day one | The archive hop is genuinely at-least-once work with a DLT requirement, which is what Kafka is for — but making it mandatory would couple ledger startup and the saga's ARCHIVE step to broker availability. `@ConditionalOnProperty` keeps exactly one publisher bean alive, HTTP preserves a working local path, and the Kafka producer is configured idempotent (`acks=all`, `enable.idempotence`) with `DELIVERY_TIMEOUT_MS=10000` specifically so a broker outage fails the ARCHIVE step fast enough for the saga to compensate instead of blocking it. |

| **Kafka rather than RabbitMQ for the event hop** | RabbitMQ exchanges/queues | The read side needs *ordered, replayable, partitioned* delivery keyed by `transactionId`, partitioned storage, consumer-group offset replay (`auto-offset-reset=earliest`) and a first-class dead-letter story (`DeadLetterPublishingRecoverer` → `ledger-events.DLT`, `FixedBackOff(1000, 3)`). A log keyed by transaction is the natural fit for an event-sourced producer; a queue would discard the ordering and the replay position. |

| **Central gateway JWT validation + trusted identity headers** (`SecurityConfig.headerInjectionFilter` injecting `X-User-Id`/`X-User-Roles`) | Each service validating the JWT itself | One JWKS client, one issuer check and one place where identity is translated; downstream services stay free of `spring-boot-starter-security` (ledger's `HeaderAuthFilter` is a plain servlet filter) and read a request-scoped `CallerContext`. The security property that makes it safe is **strip-then-inject, failing closed** — the headers are removed from every request *before* being re-added from the validated token, so a forged value cannot survive even on the fallback branch. The acknowledged cost is in the README: dev ports must not be publicly reachable, or the headers can be forged by a direct caller. |

| **Keycloak as the identity provider** (realm `ecom-bank`, realm roles `TELLER`/`MANAGER`/`AUDITOR`, public client `ecom-frontend`) | Custom login, password storage and JWT signing | Banking RBAC needs the unglamorous parts done properly: token lifetimes (`accessTokenLifespan 900`, `ssoSessionIdleTimeout 1800`), refresh-token rotation, JWKS key rollover, and a resource-server integration the gateway can consume as `issuer-uri` + `jwk-set-uri`. A hand-rolled issuer would have to reimplement all of that and would still be the weakest link. The realm is declaratively exported (`keycloak/realm-export.json`, `--import-realm`), so the three graded demo users are reproducible — with the documented sharp edge that `restart` does **not** re-import and `--force-recreate` is required. |

| **Angular 19 with PrimeNG** (`provideAuth`, `authGuard`/`roleGuard`, `authInterceptor`, `proxy.conf.json`) | React + a component library | The app is a dense data console — 16 KPI cards, data tables, a transfer wizard, a saga inspector — so it favours a batteries-included framework: first-party routing with composable functional guards (`roleGuard(['TELLER','MANAGER'])` is a one-line route annotation), a first-party HTTP interceptor chain where `authInterceptor` attaches the bearer token without touching call sites, DI-scoped singletons, and PrimeNG's Aura preset which maps directly onto the design brief's token set (`.app-dark` selector for dark mode, tabular-nums data tables, chart components). `angular-auth-oidc-client` supplies the OIDC code flow, silent renew and refresh tokens, and exposes `realm_access.roles` from the **access** token. |

| **Postgres for the ledger, H2 in-memory for the other contexts** | One database engine everywhere | The money side needs a real append-only log with `BIGSERIAL`, `NUMERIC(19,4)`, partial multi-column indexes and Flyway migrations (`ddl-auto=validate` — the schema is owned by migrations, not Hibernate). The e-commerce contexts are CRUD demo surfaces where H2 keeps a clone-and-run setup free of infrastructure, which is also why `docker-compose.yml` ships Kafka + Keycloak but no database container. |

| **Scheduled seed reconstruction instead of an import script** (`SeedRunner`, `@Profile("seed")` + `ledger.seed.enabled`, `SagaTimeline`, `SeedPacing`) | Backfilling rows with raw SQL | The seeder drives the *real* saga so seeded history has the same shape as live history — genuine events, genuine journal entries, genuine saga steps — and `SagaTimeline` gives each reconstructed run realistic inter-step spacing from a single start instant, so a backfilled saga cannot develop a torn timeline. It passes `publish=false` on the ARCHIVE step so two years of backdated activity is never re-announced to `billing-service`. Seed mode `portfolio` writes 500 customers / ~40,000 transactions over 24 months; the flag-plus-profile pairing stops an accidental profile activation from writing to a database nobody meant to seed. |

---

### Appendix — Verification Notes

- **`ARCHITECTURE.md` sources:** `docker-compose.yml`, all 8 `src/main/resources/application.properties`, `gateway-service/.../application.yml`, `config-repo/*.properties`, `keycloak/realm-export.json`, `ecom-frontend/src/app/{app.routes.ts,app.config.ts,proxy.conf.json}`, `ecom-frontend/src/app/services/auth.service.ts`, `ecom-frontend/src/app/interceptors/auth.interceptor.ts`, `ecom-frontend/src/app/guards/role.guard.ts`, `ledger-service/src/main/resources/db/migration/V1–V3`, and the Java sources named in each section above.

- **Explicitly NOT FOUND:** a Postgres (or any database) service in `docker-compose.yml`; explicit `spring.cloud.gateway.routes` / `RouteLocator` configuration in `gateway-service`; a `NewTopic` / `TopicBuilder` topic declaration for `ledger-events` or `ledger-events.DLT`; a `@KafkaListener` consuming the `.DLT` topic.
- **Discrepancy worth knowing:** `ecom-frontend/README-DEV-PROXY.md` describes the dev proxy routing *directly* to ports 8081–8085 with `pathRewrite`, but the committed `ecom-frontend/proxy.conf.json` sends all five prefixes to the gateway on `8888` with no rewrite. The config file is the operative behaviour.
