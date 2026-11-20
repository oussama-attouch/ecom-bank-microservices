# Feature: Log console (Loki inside the app)

## Business context

**Modules:** `ecom-frontend` and `gateway-service`. No business API change.

**Role:** the operator on call, not an end user. The page is `MANAGER`-only,
matching System Health and the Backoffice: the lines name accounts,
transactions and failures.

**Problem.** `specs/006-distributed-tracing-log-aggregation.md` shipped the
traceId into every log line and the lines into Loki, but the only reader is
Grafana's Explore. That is a second UI with its own URL, its own login and a
query language. The question the tracing work was meant to answer — *"what did
`ledger-service` log for this traceId?"* — still means leaving the app and
writing LogQL by hand. The System Health page describes the fleet; it cannot show
the line that explains a red row.

**What this page is.** A read-only log viewer over Loki's existing HTTP query
API, with the app's own role model. It is a *console*, not a second Grafana:

- service, level, time window and free-text filters;
- newest 500 lines of the fetched 1000;
- each line renders time, level, service, logger and message, with the
  `[traceId,spanId]` field turned into a link to the Zipkin trace;
- auto-refresh every 5 s, pausable;
- a visible LogQL preview so a reader can take the exact query to Grafana.

## Services impacted

- **`ecom-frontend`** — new route `/observability/logs`, nav item, page, HTTP
  service, dev-proxy entry.
- **`gateway-service`** — one static route for Loki's read API and one
  `MANAGER`-only security rule.
- Not impacted: the seven business services, `config-repo`, `docker-compose.yml`,
  Loki/Promtail configuration (this page consumes them as delivered), Keycloak.

## Expected behavior

### Route and navigation

- `/observability/logs`, guarded by `roleGuard(['MANAGER'])` exactly like
  `/observability/system-health`.
- A **Logs** item in the Observability nav group, hidden for anyone without
  `MANAGER` (the template's existing `hasRequiredRole` rule). The route guard
  repeats the check so a typed URL cannot reach the page.
- No new menu group; it sits beside System Health.

### The query path

The browser calls **the gateway**, never Loki directly:

```
GET /loki/api/v1/query_range?query=...&start=...&end=...&limit=1000&direction=backward
        │  (Angular dev proxy → gateway :8888)
        ▼
gateway: Path=/loki/api/v1/query_range, Method=GET (no rewrite)
        ▼
Loki:    GET /loki/api/v1/query_range
```

The `query` value is percent-encoded by a **strict** `HttpParameterCodec`
(`encodeURIComponent`), not Angular's default encoder: the default leaves `=`
raw, and a partially encoded URI trips Spring Cloud Gateway into double-encoding
the LogQL. The bug-fix section below has the measurements.

- **No prefix rewriting.** Loki serves its own API under `/loki` — its standard
  push and query prefix — which is the same prefix the browser uses. The route
  forwards the path unchanged. A first cut used `StripPrefix=1` and produced
  Loki's own `404 page not found`; the live probe caught it, and the filter was
  removed rather than guessed at.
- **Only the read path is routed.** The route's predicate is
  `Path=/loki/api/v1/query_range` **and** `Method=GET`. Loki's write path
  (`/loki/api/v1/push`) has no route at the edge and answers 404; restricting
  the only exposed verb to GET means the console route cannot become a write
  primitive even if a future security rule is loosened.
- The gateway is the auth boundary: `GET /loki/**` requires `MANAGER`. Loki
  itself stays unauthenticated on the compose network. Its published port 3100
  remains a dev convenience for `curl`, and is the one thing a real deployment
  removes from the host.
- `LOKI_URL` overrides the upstream (`http://localhost:3100` on this host,
  `http://loki:3100` in a compose-network deployment).
- `start`/`end` are nanosecond epoch strings, computed from the selected
  window; `limit=1000` and `direction=backward`.

### Filters

| Control | Behavior |
|---|---|
| Service | `All services` → `{service=~".+-service"}`; a named service → `{service="<name>"}`. The eight names are the fleet's. |
| Level | **Client-side**, over the fetched 1000 lines. Deliberate: a server-side `|~ " ERROR "` returns only the matching line and drops the stack-trace lines around it — exactly the lines that explain an error. The page fetches a window, then filters it, and a level filter keeps every RAW line in that window (they carry no level, so attributing one to a level would be invention). |
| Window | 15 m / 1 h / 6 h / 24 h, sent as `start`/`end`. |
| Text | A LogQL `|=` line filter, server-side, so a traceId finds lines older than the newest 1000. Applied on Enter or after a 350 ms debounce. |
| Auto 5 s | Pauses on demand; skips a tick while a request is in flight (`!loading`), and while the tab is hidden. |

The page caps at **500 displayed** of **1000 fetched** lines. Loki has no
concept of "page 2" for logs; narrowing the window or adding a filter is the
pagination. The footer states the cap and the LogQL preview.

### Rendering

- The line format is the one `specs/006` wrote:
  `2026-11-19 22:32:55.997 [thread] INFO  [traceId,spanId] logger - message`.
- Parsed into time, level, service, logger, message, traceId, spanId.
- A line that does not match (stack-trace continuation, framework banner) is
  `RAW`: shown full-width, muted, not discarded. This is the reason no regex
  pipeline stage exists in Promtail either.
- `ERROR` rows carry a danger rail; `WARN` rows a warning rail; the level badge
  is the colour, not a filled row.
- A traceId becomes a `View` link to
  `http://localhost:9411/zipkin/traces/<traceId>` (new tab), consistent with
  the hard-coded localhost links on System Health.
- Empty, loading and failure states are distinct. A failed poll keeps the last
  successful rows and marks them stale instead of blanking the page.
- The page says *why* it may be empty: the eight JVMs write
  `logs/<service>.log` only when started from the repository root (or with
  `LOG_DIR` set), which is the same caveat README §10.3 carries.

## Acceptance criteria

- [x] `npm run build` succeeds; `mvn -o -pl gateway-service -DskipTests package`
      succeeds.
- [ ] The Logs nav item appears for a `MANAGER` and is absent for a `TELLER`;
      `/observability/logs` typed as a teller redirects to `/dashboard`. The
      filter and guard are the same ones System Health uses; not exercised in a
      browser here.
- [x] `GET /loki/api/v1/query_range` through the gateway with a MANAGER token
      returns Loki's `status:"success"` body; without a token it is 401; with a
      teller token it is 403.
- [x] `POST /loki/api/v1/push` through the gateway is 404 (no route), not 405.
- [x] A manager query through the gateway for a known traceId returns the lines
      (the page's own query builder and parser are unit-tested; the browser
      click-through was not run here).
- [x] Filtering `ERROR` keeps the RAW lines: the component test renders one
      `.log-row` and one `.log-raw` for an ERROR + RAW + INFO fixture.
- [ ] A stopped Loki or gateway shows the banner with the last successful rows,
      not an empty page; Retry recovers. The 403 banner is component-tested; the
      full stopped-service path was not.
- [ ] Auto-refresh fetches every 5 s while visible, does not overlap requests,
      and pauses when the tab is hidden. Not exercised.
- [x] The LogQL preview is exactly the query sent: `buildLogQuery` is unit-tested
      for single service, all services, escaping and trimming; the transport is
      unit-tested to send a fully encoded `query` value.

## Bug fix: 400 Bad Request on the unfiltered query (2026-11-20)

**Symptom.** The Logs page requested:

```
http://localhost:4200/loki/api/v1/query_range
  ?query=%7Bservice=~%22.%2B-service%22%7D&start=...&end=...
```

and received **400** with Loki's body:

```
parse error at line 1, col 1: syntax error: unexpected %!(NOVERB)
```

**Diagnosis.** The Promtail label was not the problem, and neither was the
route:

- `GET /loki/api/v1/labels` returns
  `["container","filename","service","service_name","source","stream"]`, and a
  direct query on `service` returns 200 with logs.
- Direct Loki accepts **both** the raw-`=` and encoded-`=` forms of the same
  LogQL and returns 200.
- The gateway returns 200 for the **encoded** form and 400 for the **raw**
  form. The only difference is whether the `=` inside the LogQL value is
  percent-encoded.
- The Angular dev proxy at 4200 reproduces the gateway result exactly, so it
  was not mangling anything either.

**Root cause.** Angular's default `HttpParams` codec (`HttpUrlEncodingCodec`)
deliberately leaves `=` (and `/`, `?`, …) unencoded in a value. The resulting
URI is *partially encoded*. Spring Cloud Gateway's
`ServerWebExchangeUtils.containsEncodedParts()` tests
`UriComponentsBuilder.fromUri(uri).build(true)`; with a raw `=` in the query
value it treats the URI as not fully encoded, returns false, and
`RouteToRequestUrlFilter` rebuilds it with `build(false)`. That encodes the
already-encoded characters a second time — `%7B` becomes `%257B`. Loki decodes
the query parameter once, sees a literal `%7Bservice=...`, and its parser fails
at column 1 with `%!(NOVERB)`.

**Fix.** `LogService` now builds `HttpParams` with a strict
`HttpParameterCodec` that applies `encodeURIComponent` to every key and value.
The query is encoded exactly once:

```
query=%7Bservice%3D~%22.%2B-service%22%7D
```

**Regression tests.** `log.service.spec.ts` uses `HttpTestingController` to
assert that `urlWithParams` contains `%3D` (not a raw `=`), that the decoded
`query` is still the expected LogQL, and that `start`/`end` are decimal
nanosecond integers. This is the test that would have caught the bug.

**No Promtail or gateway change was required.** The label is `service`; the
route is correct; and a canonical, fully encoded request is forwarded
unchanged. The gateway still mis-handles a *partially* encoded URI, which is a
Spring Cloud Gateway limitation rather than a route configuration error. The
console now sends the canonical form. If a future non-Angular client sends a
raw `=`, the fix belongs at that client or in an explicit gateway normalisation
filter — overriding the default URI builder for every route would be a larger
change than this bug warrants.

**Verification.** See the commands in the final incident report; the key pair
is direct Loki encoded 200, gateway raw 400, gateway encoded 200, and the dev
proxy encoded 200. The full frontend suite is 127/127 and the production build
passes.

## Delivered — evidence from this environment

```powershell
npm run build                                  # production build succeeds
npm test -- --watch=false --browsers=ChromeHeadless   # 127/127
mvn -o -B -f gateway-service/pom.xml -DskipTests package  # BUILD SUCCESS
```

The route and roles were verified against the gateway on **8888** and, for
isolation while diagnosing the encoding bug, a second instance on **8889**:

| Probe against `/loki/api/v1/query_range` | Result |
|---|---|
| No `Authorization` header | **401** |
| `teller` token | **403** |
| `manager` token | **200**, Loki body `status:"success"`, a `billing-service` line |
| `POST /loki/api/v1/push`, manager token | **404** (no route) |

The two LogQL shapes the page sends both returned 200 through the gateway:
`{service=~".+-service"}` (newest 5 entries, three services) and
`{service="billing-service"} |= "dfc4ced33118a7e3d7b35e6228eabf53"` (two
entries).

Two things the live probes changed:

1. **No `StripPrefix`.** The first route stripped `/loki` and forwarded
   `/api/v1/query_range`; Loki answered `404 page not found` because its API is
   under `/loki`. The filter was removed and the path is forwarded unchanged.
   The property comment calls this out, because the wrong version looks correct.
2. **The LogQL value must be encoded by the client.** Angular's default codec
   left `=` raw; the gateway then double-encoded the LogQL and Loki answered
   400. The strict codec and the measured matrix are in the bug-fix section
   above. The gateway on 8888 already has the route and returns 200 for the
   canonical URL; this fix needs a frontend refresh, not a gateway restart.

Not run in a browser here: signing in and clicking through `/observability/logs`
(the user's gateway was intentionally left alone). The component tests cover
rendering, the trace link, the RAW-preserving level filter and the 403 banner;
the production build compiles the template with `strictTemplates`.

## Constraints

- **No new dependency.** Native `<select>`/`<input>` and `HttpClient`; no Loki
  JavaScript client, no new PrimeNG module.
- **No direct browser-to-Loki call.** The gateway proxy keeps the browser
  same-origin and puts the role check in one place. Calling `:3100` from the page
  would work on this machine and fail the first time the app is served from
  anywhere else.
- **No log writes from the page.** GET only, query_range only.
- **500/1000 caps are deliberate.** A log UI that can request an unbounded range
  is a way to take down Loki from a browser tab.
- **Client-side level filtering is a tradeoff, stated on the page:** it preserves
  stack traces, but an error older than the fetched 1000 lines needs a narrower
  window or the text filter.
- **Hard-coded localhost URLs** for Zipkin (and the Grafana link) follow
  System Health's existing convention. They are deployment constants, not user
  input; a real deployment externalises them.
- **`config-repo` is untouched:** the gateway does not import it
  (`spring.cloud.config.enabled=false`), so the route lives in its own
  `application.properties`.
- **The gateway route is static, not Eureka discovery.** Loki is not a Eureka
  service; a discovery-locator route cannot reach it.

## Out of scope

- **Live tail / WebSocket.** The 5 s poll is enough for an operator and avoids a
  second streaming protocol.
- **Arbitrary LogQL editor.** The text box is a line filter; the preview is
  read-only. Grafana Explore remains the place for aggregate queries.
- **Histograms, log-based metrics, saved queries, CSV export.** No new
  dependencies and no new Loki endpoints.
- **Grouping multi-line stack traces.** RAW lines render as they arrive; Loki
  stores them as separate entries, and inventing a grouping heuristic here
  would be a second parser with its own bugs.
- **Log access for TELLER/AUDITOR.** MANAGER-only, matching System Health.
- **Production hosting of Loki.** The gateway route works wherever `LOKI_URL`
  points; TLS, auth to Loki and network policy are deployment concerns.
