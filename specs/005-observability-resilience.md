# Feature: Observability and resilience layer

## Business context

**Modules:** all eight services. No business API change, no new endpoint on the
public surface, no frontend work.

**Role:** the operator on call, not an end user. Today a failure in this stack is
diagnosed from three places: the service console, Kafdrop, and
`ServiceHealthChecker`'s one-line-per-service log
(`ledger-service/.../health/ServiceHealthChecker.java`, 5-minute interval,
logs only). Nothing is retained, nothing is queryable after the fact, and
nothing can be alerted on.

**Problem:** the stack can prove a transfer succeeded (`journal`, `event_store`)
and can prove a message was lost (`dead_letter_transactions`), but it cannot
answer "how slow was it", "how often does it fail", or "is the ledger burning
CPU". `specs/001-dlt-consumer.md` closed the *what failed* loop; an operator
still has no time series for the half that matters most: rate and latency.

**What exists today, verified at write time:**

| Fact | Where |
|---|---|
| 7 of 8 services already depend on `spring-boot-starter-actuator`; `discovery-service` does not | 8 Ã— `pom.xml` |
| **No** service depends on `micrometer-registry-prometheus` | 8 Ã— `pom.xml` |
| `ledger-service` exposes `health,info,metrics` and states in a comment that `prometheus` is deliberately absent "because micrometer-registry-prometheus is not a dependency" | `ledger-service/src/main/resources/application.properties:147-158` |
| `config-repo/application.properties:8` and `config-repo/order-service.properties:8` both set `management.endpoints.web.exposure.include=*` | Config Server native backend |
| `gateway-service` permits `/actuator/**` unauthenticated at the edge | `SecurityConfig#springSecurityFilterChain` |
| `billing-service` and `ledger-service` build their `ConsumerFactory`/`ProducerFactory` by hand | `KafkaConsumerConfig`, `DltKafkaConsumerConfig`, `KafkaProducerConfig` |

Two consequences follow, and both drive the design:

1. **`include=*` is the effective exposure for every Config-Server-backed
   service**, because a Config Server property source outranks local
   `application.properties`. Exposing `*` publishes `/actuator/heapdump`,
   `/actuator/env`, `/actuator/configprops`, `/actuator/loggers` and
   `/actuator/threaddump`. Only one of those needs to be a *write* primitive to
   be serious: `POST /actuator/loggers` sets any logger level, so an anonymous
   caller can turn on `TRACE`; `/actuator/heapdump` serialises the whole heap,
   which is a credential-extraction primitive rather than an information leak.
2. **"Micrometer captures Kafka metrics automatically" is false here.**
   Boot's `KafkaMetricsAutoConfiguration` contributes
   `DefaultKafkaProducerFactoryCustomizer` / `DefaultKafkaConsumerFactoryCustomizer`
   beans, and `KafkaAutoConfiguration` applies them to the factories *it* creates.
   All three Kafka factories in this repo are created by hand
   (`new DefaultKafkaConsumerFactory<>(props)`), so Boot's
   `@ConditionalOnMissingBean` backs off, the customizers never run, and
   `kafka.consumer.*` / `kafka.producer.*` would be silently absent. Shipping a
   "metrics foundation" with that hole in it is the specific failure mode this
   spec exists to avoid.

## Services impacted

- **All eight** â€” `pom.xml`, `application.properties`, and the shared
  `config-repo` properties.
- **`billing-service`** â€” one new health indicator, Kafka metrics listeners.
- **`ledger-service`** â€” Kafka producer metrics listener.
- **`docker-compose.yml`** â€” Prometheus and Grafana. *This spec names that
  milestone, which is the condition `AGENTS.md` sets for touching the file;
  `specs/001-dlt-consumer.md` declined to touch it for exactly that reason.*
- Not impacted: `ecom-frontend` (explicitly out of scope), `keycloak/`.

## Expected behavior

### Step 1 â€” Observability foundation (this milestone)

1. `micrometer-registry-prometheus` in all 8 modules, version inherited from
   `spring-boot-dependencies` (Micrometer 1.13.6 for Boot 3.3.5), so it cannot
   drift from the Micrometer core the build already resolves.
   `spring-boot-starter-actuator` added to `discovery-service`, the one module
   without it.
2. `management.endpoints.web.exposure.include=health,info,metrics,prometheus`
   in all 8 services **and** in both `config-repo` overrides. No `*` anywhere.
3. `management.endpoint.health.show-details=when-authorized`, overridable with
   `HEALTH_SHOW_DETAILS` for local work.
4. Histogram + SLO buckets on `http.server.requests` in every service, so a
   cross-instance p95 is computable (`histogram_quantile` over
   `http_server_requests_seconds_bucket`) instead of a per-instance `_max`.
5. A custom `HealthIndicator` in `billing-service` reporting consumer-group lag
   for `billing-service` and `billing-service-dlt`, cached and refreshed off the
   request thread, plus a `kafka.consumer.lag` gauge for alerting.
6. `micrometer` listeners attached to the three hand-built Kafka factories.
7. `prometheus.yml` for the whole stack, plus `docker-compose.yml` services for
   Prometheus and Grafana with a provisioned datasource.

### Steps 2â€“5 â€” later milestones, not in this one

Kubernetes probes (2), Resilience4j (3), gateway active health checks (4),
OpenTelemetry tracing (5). Each is delivered and reviewed separately.

## Acceptance criteria

- [ ] `mvn -o -B -DskipTests package` succeeds; every module can resolve
      `micrometer-registry-prometheus`.
- [ ] `curl localhost:8081/actuator/prometheus` returns 200 and its body
      contains `jvm_memory_used_bytes`, `http_server_requests_seconds_bucket`
      and `hikaricp_connections`.
- [ ] `curl localhost:8081/actuator/health` returns 200 and, with no principal,
      **no** component details. `HEALTH_SHOW_DETAILS=always` overrides locally.
- [ ] `/actuator/heapdump`, `/actuator/loggers`, `/actuator/env` and
      `/actuator/configprops` all return 404 on every service.
- [ ] `curl localhost:8083/actuator/health` on `billing-service` includes a
      `kafkaLag` component (as detail, subject to the same authorization rule).
- [ ] With the broker down, a call to `/actuator/health` returns without
      blocking on a Kafka timeout, and the indicator reports DOWN.
- [ ] `mvn -o test` passes; the eight `*ApplicationTests` context loads still
      start with the new beans present.
- [ ] `docker-compose up -d prometheus grafana` â†’ `localhost:9090/targets` shows
      every running service UP, and Grafana's Prometheus datasource is
      pre-provisioned and green.

## Constraints

- **`spring-boot-starter-security` is not added to any downstream service**
  (`AGENTS.md`). Consequence, stated plainly so nobody is surprised:
  `show-details=when-authorized` resolves to *never* on those services, because
  there is no authentication provider to authorise. That is the fail-closed
  direction and it is why `HEALTH_SHOW_DETAILS` exists.
- **`/actuator/prometheus` is unauthenticated**, which is normal for a pull
  scraper. On `gateway-service` that endpoint sits behind a chain that already
  `permitAll`s `/actuator/**`, so the exposure must be narrowed at the network
  layer in any real deployment â€” the documented fix is
  `management.server.port`, given below as an opt-in, not a default, because it
  changes every probe target.
- **One deliberate exception to "feature gates default OFF".** The lag
  indicator is **on** when present, and is switched off with the Actuator-native
  `management.health.kafkaLag.enabled=false` rather than a bespoke
  `@ConditionalOnProperty` flag. Rationale: a monitoring feature that ships
  absent is a monitoring gap, and `AGENTS.md`'s rule protects unreleased
  business surfaces, not the observability plane. The switch is still an
  environment variable, so the "environment-overridable" requirement holds.
- **`.properties`, not `.yml`.** All eight services are `.properties` today;
  `config-repo` overrides them by precedence. Introducing a second loader for
  the same location makes "which value wins" a question, for no benefit.
  Kubernetes probe YAML is unaffected and is still YAML.
- **No business logic changes.** `KafkaConsumerConfig`/`DltKafkaConsumerConfig`
  gain a `MeterRegistry` parameter and a listener. The error handler, the
  recoverer, the retry budget and the DLT's no-recoverer guarantee are untouched
  and stay as `docs/CONTEXT/project-state.md` requires.
- **Two config planes, both edited.** A Config Server property source outranks
  local `application.properties`, so narrowing only the service files would
  leave `include=*` winning for `customer`, `inventory`, `billing` and `order`.
  Both planes are therefore changed in the same commit.

## Out of scope

- **Alerting rules.** No `alert.yml`, no Alertmanager, no paging. Step 1 ends at
  "the numbers are scrapable".
- **Grafana dashboards as a deliverable.** Datasource provisioning is in scope;
  panel layout is not curated in this milestone.
- **A Kafka health indicator for `ledger-service`.** Its Kafka dependency is a
  *producer*. A broker outage there is already bounded by
  `delivery.timeout.ms=10000` so the ARCHIVE step fails fast and the saga
  compensates; the number that matters is outbox backlog age, which is a metric
  to derive from `outbox_events`, not a health check. Deferred with the reason
  recorded rather than covered by a check that measures the wrong thing.
- **`ecom-frontend`.** No browser RUM, no `/metrics` on the Angular dev server.
- **Log aggregation.** Step 5 adds trace IDs to log lines; shipping them
  somewhere is a separate decision.
- **Jackson/HTTP client metrics tuning, `management.server.port` in the default
  configuration.** Documented, not enabled.

## Step 2 — Kubernetes probes (delivered)

`management.server.port=${MGMT_PORT:<app port + 10000>}` moves the whole
management surface off the application port, so `/actuator/**` cannot be reached
through the listener that serves business traffic. Two consequences had to be
repaired in the same change or the stack would have broken silently:

- `monitoring/prometheus/prometheus.yml` now scrapes the management ports.
- `ledger-service`'s `ServiceHealthChecker` now polls them too; it had been
  polling `/actuator/health` on the application port, which after this change
  would 404 on all eight services.

**Liveness never touches an external system.** `livenessState,ping` only — a
failing database must not restart a healthy JVM, because a restart cannot fix a
dependency and does destroy a pod that was still serving cached reads.

**Readiness does.** `readinessState,db` (plus `kafkaLag` in `billing-service`),
so traffic is only routed where the service can actually serve.

**`LAGGING` does not fail readiness.** The status is declared in
`management.endpoint.health.status.order` and mapped to HTTP 200 in
`http-mapping`, and every health response — the endpoint and each group — is
rendered through the same `HttpCodeStatusMapper`. So a lagging group yields
`{"status":"LAGGING"}` with 200, the probe passes, and the pod stays in
rotation. `DOWN` (broker unreachable, not merely behind) still yields 503.

## Step 3 — Resilience4j (delivered)

`resilience4j-spring-boot3` 2.2.0 + `spring-boot-starter-aop`, pinned through the
`resilience4j-bom` (the Boot BOM does not manage it).

- `order-service` — `OrderRestController.getOrder`, the only real fan-out in this
  repository: one read against `customer-service` and one per order line against
  `inventory-service`. It is a **read**, so a degraded answer is legitimate, and
  the fallback returns a typed 503 `ApiError` rather than a 500 stack trace.
- `billing-service` — `ArchiveObservationNotifier.archived`, whose documented
  contract is "it must never matter". CircuitBreaker + Bulkhead, no Retry: the
  call is fire-and-forget telemetry, so retrying only multiplies load for a
  notification nobody blocks on.
- `gateway-service` — the starters are present, but **annotations are not used
  there and that is deliberate**. `KeycloakAdminClient` returns `Mono`, and the
  Resilience4j aspects decorate the *method invocation*: the method returns a
  `Mono` immediately, so a 5xx signalled inside the reactive pipeline never
  reaches the aspect. The annotations would be decorative. Reactor-native
  isolation (`CircuitBreakerOperator`, or the gateway's `CircuitBreaker` route
  filter) is a separate piece of work with a different dependency.

**Not applied to the ledger write path, on purpose.** `HttpTransactionEventPublisher`
failing is how the saga learns to compensate. A fallback there that returned
"published" would mark a dropped event as archived, and a Retry would risk a
second archive row for one transaction. The outbox already is the retry layer;
adding an HTTP retry underneath it duplicates the mechanism instead of
strengthening it.

## Step 4 — Gateway active health checks (configuration delivered)

`spring.cloud.loadbalancer.configurations=health-check` with a 15 s interval.

**Blocked by the management-port split, and this is the one place the two
rulings conflict.** `HealthCheckServiceInstanceListSupplier` builds its probe URL
as *instance URI + path*, where the instance URI carries the port a service
registered with Eureka — its **application** port. With `/actuator/health` moved
to the management port, every background poll gets a 404, every instance is
classed DOWN, and the gateway answers 503 on every route. The path is
configurable; the port is not.

Three ways out, none of them free:

1. keep this as configuration but leave `management.server.port` unset on the
   services the gateway routes to (re-opens `/actuator/**` on the app port);
2. port-forward / network policy so the management port is reachable at the
   instance URI (no application change, deployment complexity);
3. use `eureka.client.healthcheck.enabled=true` so each instance *reports its
   own* readiness into Eureka and the gateway's default discovery supplier drops
   the DOWN ones — in-process, so the port split does not matter.

`AGENTS.md` forbids implementing without a decision, so option 3 is written
below and option 1's configuration is shipped **commented out**. Pick one and the
step closes.

## Step 5 — Unified monitoring dashboard (delivered)

Two gateway endpoints plus one Angular page. `gateway-service` gains
`GET /api/admin/system-health` and `GET /api/admin/circuit-breakers`, both
`MANAGER`-only.

**Angular, not React.** `ecom-frontend` is Angular 19 + PrimeNG 19; there is no
React, no `react-dom` and no Tailwind on disk. The page is
`src/app/components/observability/system-health/`, written as a standalone
`OnPush` component with `templateUrl`/`styleUrl` like its five siblings, and it
uses the existing tone-variable design system rather than introducing a second
styling model.

**`MANAGER`, not `ADMIN`.** The realm holds TELLER/MANAGER/AUDITOR
(`keycloak/realm-export.json`); `hasRole('ADMIN')` compiles, deploys and answers
403 to every holder of a real token. `MANAGER` is what every other administrative
surface here already requires.

**The management port is discovered, not computed.** `appPort + 10000` is true on
the shared developer host and false in a pod, where `k8s/ledger-service.yaml`
sets `MGMT_PORT=8081`. Each service therefore publishes what it actually bound:

- `app.management-port=${MGMT_PORT:<default>}` is the single source of truth;
- `management.server.port=${app.management-port}` binds it;
- `eureka.instance.metadata-map[management.port]=${app.management-port}`
  advertises it.

One property feeding both is deliberate: two independent
`${MGMT_PORT:<default>}` spellings would drift the first time someone changed one
default, and the symptom would be a probe against the wrong port returning 404
and reading as "service DOWN". The aggregator prefers the metadata and falls back
to the offset; it reads both `management.port` and `management-port`, because a
plain `metadata-map.management-port` binds the dashed key.

**Circuit breakers are collected, not merely read.** `CircuitBreakerRegistry` is
per-JVM, and the gateway's own registry is empty — it has no annotated call sites
(the annotations do not work on a reactive `Mono`, see Step 3). An endpoint
reading only the local registry would answer "nothing is open" forever, which is
worse than no endpoint: a green light never connected to anything. The controller
reports the local registry *and* fans out to each service's
`/actuator/circuitbreakers`, merging both; a service that does not expose the
endpoint is skipped, not shown as broken. `circuitbreakers` was added to
`config-repo/{billing,order}-service.properties` for exactly that reason — the
Config Server property source outranks the service's own file.

**`details` is normally empty and the field is nullable.** Downstream
`show-details=when-authorized` resolves to never (no Spring Security on those
classpaths, by design), so the aggregator receives `{"status":"UP"}` and nothing
more. Null distinguishes "no details offered" from "healthy with nothing to
report"; turning them on is `HEALTH_SHOW_DETAILS=always` on a management network
only the gateway can reach.

**Latency and failure isolation.** Each probe has a 2 s connect and 2 s response
timeout and runs in parallel; a probe that throws becomes a `DOWN` row carrying
the reason rather than failing the aggregate. The page polls every 5 s through
`exhaustMap`, so a slow aggregator cannot stack requests — the failure mode that
matters exactly when the gateway is unwell.

**Grafana is embedded, which requires two settings.** `GF_SECURITY_ALLOW_EMBEDDING=true`
or the `<iframe>` is replaced by a refusal, and anonymous `Viewer` or the frame
redirects to a login form it cannot show. Both are in `docker-compose.yml` with
the reason inline. The dashboard is provisioned from
`monitoring/grafana/dashboards/ecom-bank-overview.json` under uid
`ecom-bank-overview`, which is the UID the iframe URL resolves.

**Dev proxy.** `proxy.conf.json` gained `/api` ? `localhost:8888`. The other
entries are `/{service-id}/**` prefixes; `/api/admin/**` is served by the gateway
itself and matches none of them, so without this line the dev server answers 404
and the page reads as broken.

**Not verified in this environment.** `npm run build` cannot run here: esbuild
fails `spawn EPERM` under the sandbox. The Angular component was reviewed, not
compiled — run it before merging. The aggregator itself was not exercised against
a live Eureka either (Docker is unavailable here, so Keycloak cannot issue a
token); its evidence is a successful context start with all new beans present.
