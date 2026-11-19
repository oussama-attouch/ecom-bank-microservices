# Feature: Distributed tracing and centralized log aggregation

## Business context

**Modules:** all eight services. No business API change, no new endpoint on the
public surface, no frontend work.

**Role:** the operator on call, not an end user. The failure being fixed is not a
missing feature; it is the time between "the gateway returned a 500" and "this is
the line in `ledger-service` that threw".

**Problem.** The observability layer added by `specs/005-observability-resilience.md`
answers *how much* and *how fast*: Prometheus has the rate, the p95 and the 5xx
count, and Grafana draws them. It cannot answer *which request*. A 500 at the edge
is one number in a counter; the order that triggered it and the ledger write that
failed are two unrelated process consoles, and the only identifier shared by the
three is a timestamp. Meanwhile the eight logs are not retained anywhere: a
restart destroys the evidence, and "show me what happened at 14:32" means opening
eight terminals.

Two facts about this repository shape the design and are not assumptions:

1. **The services run on the host, not in Docker.** `docker-compose.yml` starts
   Kafka, Keycloak, Prometheus and Grafana; the eight JVMs are started with
   `mvn spring-boot:run` or a jar. Their stdout is therefore *not* a Docker log,
   and a Promtail configured only for container logs would aggregate the
   infrastructure and none of the application — the exact opposite of what this
   milestone is for.
2. **Three Kafka factories and both KafkaTemplates are hand-built.**
   `billing-service/KafkaConsumerConfig`, `billing-service/DltKafkaConsumerConfig`
   and `ledger-service/KafkaProducerConfig` construct their own factories, so
   Boot's `KafkaAutoConfiguration` backs off (`@ConditionalOnMissingBean`) and
   `spring.kafka.listener.observation-enabled` /
   `spring.kafka.template.observation-enabled` never reach them. This is the same
   hole `specs/005-observability-resilience.md` documented for Micrometer metrics,
   and it would silently make every async flow untraceable.

**What this milestone delivers.** A W3C trace context that starts at the gateway
and survives Feign, Kafka headers, and the DLT; a log line in every service that
carries the same `traceId` and `spanId` without any application code passing them
around; Zipkin as the trace view; and Loki as the place the lines land, with a
link from a line to its trace.

## Services impacted

- **All eight** — two dependencies and the tracing/logging properties in each
  `pom.xml` / `application.properties`. `discovery-service` and `config-service`
  are infrastructure and produce almost no request spans of their own; they are
  included because "all eight" is a property of the fleet, and a service that is
  the only one not exporting is the one whose absence is noticed at the worst
  time.
- **`billing-service`** — explicit observation on two hand-built listener
  container factories and the hand-built `KafkaTemplate`.
- **`ledger-service`** — explicit observation on the hand-built `KafkaTemplate`;
  the Boot-built listener factory is switched on by property.
- **`docker-compose.yml`** — Zipkin, Loki and Promtail. *This spec names that
  milestone, which is the condition `AGENTS.md` sets for touching the file.*
- **`monitoring/`** — Loki and Promtail configuration, one Grafana datasource.
- Not impacted: `ecom-frontend`, `keycloak/`, `config-repo/` (see Constraints),
  Kubernetes manifests (the new properties are environment-overridable; a
  manifest that needs them is a deployment change, not this one).

## Expected behavior

### Step 1 — Distributed tracing (Micrometer + OpenTelemetry)

Every module gains:

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
  <groupId>io.opentelemetry</groupId>
  <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

No `<version>` on either. `spring-boot-dependencies` 3.3.5 imports
`micrometer-tracing-bom` 1.3.5 and `opentelemetry-bom` 1.37.0, so the bridge
cannot drift from the `micrometer-observation` / `micrometer-core` the actuator
already pulls in, and the exporter cannot drift from the API the bridge was
compiled against. Both artifacts are required: the bridge creates and samples
spans, and without an exporter those spans are given IDs and discarded.

Configuration, identical in all eight services:

| Property | Value | Why |
|---|---|---|
| `management.tracing.enabled` | `${TRACING_ENABLED:true}` | One switch to take tracing out without a rebuild. |
| `management.tracing.sampling.probability` | `${TRACING_SAMPLING_PROBABILITY:1.0}` | 100% for the demo. 0.1 is a sane production start. |
| `management.zipkin.tracing.endpoint` | `${ZIPKIN_ENDPOINT:http://localhost:9411/api/v2/spans}` | The collector path, not the UI path. |
| `management.tracing.propagation.produce` | `W3C` | `traceparent`, understood by every hop here. |
| `management.tracing.propagation.consume` | `W3C,B3,B3_MULTI` | A B3 caller joins the trace instead of starting a second one. The B3 propagator arrives transitively with the bridge. |

Sampling is a **root decision**: Boot builds a `parentBased` sampler, so the
gateway decides once and every downstream span inherits the decision. A service
cannot sample a trace its parent dropped, which is what makes a 100% demo
coherent rather than eight independent 100% samplers with broken parent links.

**HTTP propagation is automatic, and that is not the same as automatic
everywhere.**

- Gateway: `GatewayMetricsAutoConfiguration.ObservabilityConfiguration` registers
  `ObservedRequestHttpHeadersFilter` and, when a `Tracer` bean is present,
  `GatewayPropagatingSenderTracingObservationHandler`. The outbound `traceparent`
  is written by the gateway filter, not by the route's WebClient configuration.
- Feign (`order-service` → `customer-service` / `inventory-service`,
  `billing-service` → `ledger-service`): `FeignClientsConfiguration` registers
  `MicrometerObservationCapability` when an `ObservationRegistry` bean exists
  (`spring.cloud.openfeign.micrometer.enabled` defaults true), so the client span
  and the header injection are part of the Feign call.
- Downstream MVC services: Boot's `ServerHttpObservationFilter` creates the
  server span and continues the parent.
- Kafka: see below — it is the one hop that needs code.

### Kafka propagation — how it works and why it needed a change here

Spring Kafka and Micrometer split the job between them:

1. `KafkaTemplate#send` starts an observation (`KafkaTemplateObservation`) whose
   context is a `KafkaRecordSenderContext` wrapping the `ProducerRecord`.
   Micrometer Tracing's `PropagatingSenderTracingObservationHandler` injects the
   W3C `traceparent` into **that record's headers** — the record is the carrier,
   there is no side channel.
2. On the consumer side, `KafkaMessageListenerContainer` starts
   `KafkaListenerObservation` with a `KafkaRecordReceiverContext`. The receiver
   handler extracts `traceparent` from the record headers and makes the CONSUMER
   span a child of the producer's span. The consumer's traceId is in the MDC for
   the whole listener invocation, so the service's own log lines (and the DLT
   republish) carry it.
3. The scope is opened around `invokeOnMessage`, so anything the listener logs —
   including an exception and the error handler's retry/DLT path — is correlated.

The switch differs by how the factory was built:

- **Boot-created listener factory** (`ledger-service`'s live-stream listener):
  `spring.kafka.listener.observation-enabled=true`.
- **Hand-built factories** (`billing-service`, both listeners): the Boot property
  is inert because `KafkaAnnotationDrivenConfiguration` only configures the
  factory *it* creates. Observation is enabled on the
  `ContainerProperties`/`KafkaTemplate` directly in the three configuration
  classes.
- **Hand-built templates** (`billing-service`, `ledger-service`): the Boot
  property is inert for the same `@ConditionalOnMissingBean` reason;
  `setObservationEnabled(true)` is called where the template is constructed. The
  template resolves the `ObservationRegistry` itself in
  `afterSingletonsInstantiated()`.

Consequences worth knowing, because they are visible in Zipkin:

- The producer and consumer spans are **not adjacent in time**: the consumer span
  starts when the message is consumed, which may be milliseconds or minutes
  after the producer span ended. That gap is the queue, and it is the number to
  read when "Kafka is slow" is the complaint.
- The DLT republish happens **inside** the failed consumer span
  (`DeadLetterPublishingRecoverer` uses the observed `KafkaTemplate`), so the
  poison record on `ledger-events.DLT` and the row persisted by the DLT consumer
  share the original traceId. This is the one place where tracing a failure
  across retries is exact rather than reconstructed.
- **The outbox relay starts a new trace.** `ledger.outbox.enabled=true` writes
  the event in the request's transaction but publishes it later from
  `OutboxPublisher`, and `traceparent` is not persisted in `outbox_events`.
  The publish is therefore a root span. The direct Kafka path
  (`ledger.publisher=kafka`, outbox off) is continuous. Persisting the context
  is a schema change and is out of scope here; it is recorded rather than
  papered over.

### Step 2 — Log correlation (MDC), without passing IDs around

With the OTel bridge, Boot's `OpenTelemetryAutoConfiguration` registers an
OpenTelemetry `Slf4JEventListener` (`otelSlf4JEventListener`). On every span
start it writes `traceId`, `spanId` and `traceFlags` into SLF4J's MDC, and on
span end it restores the previous state. Micrometer Tracing starts that span as
part of the observation it already creates for an HTTP request, a Feign call or
a Kafka record, so the application code does nothing: `log.info(...)` inside a
request handler or a `@KafkaListener` automatically renders the current IDs.

Every service carries the same two lines:

```properties
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n
logging.pattern.file=%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level [%X{traceId:-},%X{spanId:-}] %logger{36} - %msg%n
```

- The file pattern is not redundant: the JVMs run on the host, Promtail runs in
  the compose network, and the file is the only reliable bridge between them.
- `:-` (empty default) keeps startup, scheduled jobs and pool threads honest:
  outside a trace the field is `[,]`, not `[null,null]`.
- The bracket and comma shape is a contract with two consumers: Grafana's
  derived field (`[traceId,spanId]` → Zipkin link) and the verification LogQL.
  It is stated in the properties file for that reason.
- `logging.file.name=${LOG_DIR:logs}/${spring.application.name}.log` writes
  `logs/<service>.log`; the rolled-file pattern keeps a `.log` suffix so
  Promtail's `*.log` glob continues to match after rotation.
- **No `logback-spring.xml`.** A per-module logging config would be eight files
  to drift; the properties are identical and reviewable in one glance, which is
  the repo's `.properties`-not-YAML ruling applied to logging.

### Step 3 — Centralized logging (Loki + Promtail)

`docker-compose.yml` gains three containers:

| Service | Port | Role |
|---|---|---|
| `zipkin` | 9411 | Trace store + UI. In-memory storage, so a restart empties it. |
| `loki` | 3100 | Log store, single-binary, filesystem + TSDB, 7-day retention. |
| `promtail` | 9080 | Shipper. `/targets` on 9080 is the debugging surface. |

Promtail has **two jobs**, because this stack has two kinds of log:

1. `ecom-bank` — `static_configs` over `/var/log/ecom-bank/*.log`, which is the
   host's `./logs` bind-mounted into the container. `service` is relabelled from
   the file name, so `{service="ledger-service"}` works without the application
   emitting a label.
2. `docker` — `docker_sd_configs` over the Docker socket for the infrastructure
   (Kafka, Keycloak, Grafana, ...). Promtail drops its own container to avoid
   shipping its own push log back through itself.

There is deliberately **no regex pipeline stage** on the application lines. A
`regex` stage drops lines that do not match, and stack-trace lines are exactly
the lines that do not match the application pattern; losing them would defeat
the purpose. Level and logger are not promoted to labels for the same reason:
the traceId must not become an index label (one series per request is the
cardinality problem Loki is known for), and the line already contains it.

Grafana gets its Loki datasource from
`monitoring/grafana/provisioning/datasources/loki.yml`, with a derived field
that turns `[traceId,spanId]` into a "View trace in Zipkin" link to
`http://localhost:9411/zipkin/traces/<traceId>`. The URL is browser-facing, so it
uses `localhost`, not the compose service name.

## Acceptance criteria

- [x] `mvn -B -DskipTests package` succeeds; every module resolves
      `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-zipkin`.
- [x] `docker-compose up -d zipkin loki promtail grafana` → `curl
      localhost:9411/health` 200, `curl localhost:3100/ready` 200, and
      `localhost:9080/targets` lists the `ecom-bank` job with the log files of
      the services that have been started (4/8 in this environment; the other
      four were running pre-change jars).
- [x] Grafana's datasources API lists `Loki` with uid `ecom-bank-loki` and a
      derived field whose URL is
      `http://localhost:9411/zipkin/traces/${__value.traceid}`.
- [x] One request through the gateway produces one Zipkin trace containing the
      gateway span and the downstream customer-service span under the **same
      traceId** (`145d94210f7a4dfc5cc96564cd32f1b2`, 7 spans).
- [x] The same id appears in the log lines emitted while handling a request —
      proven on the HTTP→Kafka path, because `customer-service` does not log per
      request: ledger-service's HTTP lines and billing-service's consumer line
      share `dfc4ced33118a7e3d7b35e6228eabf53`.
- [x] `curl "localhost:3100/loki/api/v1/query_range?query={service=\"billing-service\"}
      |= \"<traceId>\""` returns the line.
- [x] A Kafka record carrying `traceparent` is consumed by `billing-service`
      with the **same traceId** in its log, and Zipkin shows the consumer span
      under that trace's id (`7861e31bbd154102ae00b41cb198cf58`).
- [x] A record that exhausts its retries keeps the trace: the DLT consumer's
      log line carries the original traceId (`da76beb0cbe8444e83ae10696ff67bf9`).
- [x] A line logged outside any request (startup) renders `[,]`, not
      `[null,null]`.
- [x] Existing tests pass (`mvn -o test`, 267 tests, 0 failures), with one
      test-isolation repair documented below.

## Delivered — evidence from this environment

The commands below are the ones that produced the evidence, written for
PowerShell because this repository's host is Windows. `curl.exe`, not `curl`,
avoids PowerShell's `Invoke-WebRequest` alias.

```powershell
# 1. The observability plane
docker compose up -d zipkin loki promtail grafana
curl.exe -s http://localhost:9411/health          # {"status":"UP", ...}
curl.exe -s http://localhost:3100/ready           # ready
curl.exe -s http://localhost:9080/targets         # ecom-bank job, one target per file

# 2. Build, then start the services from the repository root so logs/ is the
#    directory docker-compose mounts into Promtail.
mvn -B -DskipTests package
java -jar ledger-service/target/ledger-service-0.0.1-SNAPSHOT.jar --ledger.publisher=kafka
java -jar billing-service/target/billing-service-0.0.1-SNAPSHOT.jar
# plus discovery-service, config-service, customer-service, gateway-service for the HTTP leg

# 3. One request that crosses HTTP and Kafka.
$body = '{"type":"CREDIT","accountId":"ACC-004B4EA0","amount":1.23,"description":"tracing demo"}'
[System.IO.File]::WriteAllText("$env:TEMP\credit.json", $body)
curl.exe -s -H "Content-Type: application/json" -X POST `
  --data-binary "@$env:TEMP\credit.json" http://localhost:8085/api/transactions
# 201 Created

# 4. Find the trace and its log lines.
$trace = ((curl.exe -s "http://localhost:9411/api/v2/traces?serviceName=ledger-service&limit=1" |
  ConvertFrom-Json)[0])[0].traceId
curl.exe -s "http://localhost:9411/api/v2/trace/$trace"
Select-String -Path logs\ledger-service.log,logs\billing-service.log -Pattern $trace
curl.exe -s "http://localhost:3100/loki/api/v1/query_range?query=%7Bservice%3D%22billing-service%22%7D%20%7C%3D%20%22$trace%22&limit=5"
```

Observed, for the request above (trace `dfc4ced33118a7e3d7b35e6228eabf53`):

| Service | Span | Parent | Log line (abridged) |
|---|---|---|---|
| ledger-service | `http post /api/transactions` (SERVER) | — | `[dfc4ced3…,a2916b3a…]` |
| ledger-service | `ledger-events send` (PRODUCER) | `a2916b3a…` | `[dfc4ced3…,ec69d53e…] ... Published transaction … to Kafka topic ledger-events` |
| billing-service | `ledger-events receive` (CONSUMER) | `ec69d53e…` | `[dfc4ced3…,7e82787d…] ... Archived transaction … (from Kafka)` |

The DLT case (trace `da76beb0cbe8444e83ae10696ff67bf9`) shows the failure path in
one trace: four `ledger-events receive` CONSUMER spans (the first attempt plus
three retries), then `ledger-events.dlt send` PRODUCER, then
`ledger-events.dlt receive` CONSUMER. The `DLT_CONSUMER:` ERROR line in
`billing-service` carries the same traceId — so the row that says *what was
lost* also says *which request lost it*.

The gateway leg (trace `145d94210f7a4dfc5cc96564cd32f1b2`) contains the gateway's
`http get`, its security spans, and customer-service's
`http get /api/customers` as a child of the gateway's outbound span — the proof
that `ObservedRequestHttpHeadersFilter` injected `traceparent` and the downstream
server accepted it. Gateway → customer was exercised with a real Keycloak token
for `teller`; the trace is searchable by `serviceName=gateway-service`.

Two things the evidence changed in the implementation:

1. **Promtail's file job needed one static config per service, not a glob plus
   a relabel.** Promtail relabels the *target*, and a file target's `__path__`
   is the glob (`/var/log/ecom-bank/*.log`) at that point, so a regex over it
   extracted `*` and produced `service="*"`. That was measured, then replaced by
   eight explicit paths with fixed `service` labels.
2. **The Docker job drops Kafka's containers.** A Docker-SD target with no
   positions entry starts at offset 0, so the first scan reads months of broker
   log; Loki's `reject_old_samples` then rejects it and Promtail logs a
   "timestamp too old" error per batch. The broker log remains available through
   `docker logs kafka`, and application↔Kafka behaviour is read from the trace and
   the consumer's own log. Promtail is quiet with the drop in place.

Enabling observation also changes Spring Kafka's listener meters: the old
`kafka.listener.*` timers are replaced by observation-based
`spring_kafka_listener_*` meters. No dashboard or alert in this repository
queries the old names (the overview dashboard queries the health indicator's
`kafka_consumer_group_lag`), so nothing broke; a future dashboard should use the
observation names.

**One test repair, stated plainly.** `billing-service`'s `contextLoads` test
loads the real application, whose `CommandLineRunner` calls customer-service and
inventory-service on startup. That call races the test client's first Eureka
registry fetch and fails `UnknownHostException: inventory-service`; it passed
alone and failed twice in a full `mvn test` run once the tracing auto-config
widened the startup window. The test now replaces the two Feign clients with
mocks and stubs them from a `HIGHEST_PRECEDENCE` runner, which runs before the
seeding runner. Nothing in application code changed for this.

## Constraints

- **Versions are inherited, never pinned.** Both artifacts are managed by
  `spring-boot-dependencies` 3.3.5 (Micrometer Tracing 1.3.5, OpenTelemetry
  1.37.0). Pinning a version in a module would reintroduce the drift the actuator
  and Micrometer Tracing share a BOM to prevent. This satisfies the
  "no new dependency without an ADR" rule through the spec itself plus inline
  comments, as `AGENTS.md` allows.
- **100% sampling is a demo setting, stated as such.** Every actuator probe and
  every Kafka record becomes a trace, and the Zipkin container holds roughly
  500k spans in memory. The sampling property is environment-overridable for that
  reason.
- **No business logic changes.** The only Java edits are configuration classes
  that already exist: two lines in `billing-service` (observation on two
  container factories and one template) and one in `ledger-service` (one
  template). Error handling, retry budgets, DLT semantics and the outbox are
  untouched.
- **No schema changes, no Flyway migration.** The outbox trace-context gap is
  documented, not closed, because closing it requires a column.
- **`config-repo` is deliberately untouched.** The four Config-Server-backed
  services get their local values because the Config Server has no conflicting
  key; the other four do not import it at all. Duplicating the block into
  `config-repo/application.properties` would be dead configuration for half the
  fleet and a second place to drift. (Contrast `specs/005`: there, `config-repo`
  *set a different value*, which is why both planes had to change.)
- **The `.log` file appender is added to all eight services.** It is what makes
  host-run logs collectable without changing how they are started. `LOG_DIR`
  exists because the working directory differs between `mvn spring-boot:run`,
  a jar from the repo root, and a container.
- **Observability services are unauthenticated, by design of the demo.**
  Loki's push path with `auth_enabled: false`, Zipkin's API and Grafana's
  anonymous Viewer are all reachable on localhost. The docker-compose comments
  say where the network boundary belongs in a real deployment. Promtail's
  read-only Docker socket is root-equivalent on the host and is a
  developer-machine arrangement.
- **Zipkin retention is memory.** A restart is an empty trace list. The demo
  values reproducibility over durability; a production store is Elasticsearch or
  Tempo.

## Out of scope

- **OTLP / Grafana Tempo.** Zipkin is the requested backend and the exporter is
  a two-line swap; running both would double the containers for no debugging
  capability.
- **Alerting on traces or logs.** No LogQL alert rules, no Zipkin-derived SLOs.
- **Tail sampling and PII scrubbing.** At 100% every trace is kept; header
  capture is not enabled, so tokens are not in the span attributes, but nothing
  audits the payloads either.
- **Exemplars from Prometheus to traces.** The metrics page already exists; a
  histogram exemplar carrying a traceId is a separate change to the scrape and
  dashboard path.
- **Persisting `traceparent` in `outbox_events`.** Recorded as a known gap.
- **Kubernetes manifests.** The new properties are `ZIPKIN_ENDPOINT` /
  `LOG_DIR` overridable; a pod that runs in a cluster sets them. No manifest in
  this milestone.
- **Frontend RUM / browser spans.** `ecom-frontend` is untouched.
- **Log-based metrics and a Loki dashboard.** The datasource and the log view
  are the deliverable; panel design is not.
