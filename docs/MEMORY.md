# Project Memory

## Current State
- Current Phase: **Maintenance & Documentation**
- Repo: E-Com Bank (8 services, Angular frontend)
- Status: 8/8 services running, ledger balanced, tests passing.

## Lasting Learnings (Feedback)
- Run `mvn -o` for offline builds to avoid DSH tool timeouts. Offline works only
  after one online resolve of newly added artifacts.
- Never use PowerShell `Out-File -Encoding utf8` for source files (BOM).
- Do not run detached processes; use the harness background job runner.
- Keycloak realm changes require `docker-compose up -d --force-recreate keycloak`.
- Promtail relabels a **file target**, not each discovered file: a `service`
  label extracted from `__path__` sees the glob (`*.log`), not the basename.
  List one `static_configs` entry per service instead of regexing the path.
- The billing `contextLoads` test races Eureka discovery: its `CommandLineRunner`
  calls `inventory-service` before the test client's first registry fetch. It
  passes alone and can fail in a full `mvn test` run; that failure is
  environmental, not caused by the change under test.
- Loki serves its API under **`/loki`**: `/loki/api/v1/query_range` is the real
  path, and `/api/v1/query_range` is a 404. A gateway route to Loki must forward
  the path unchanged; `StripPrefix=1` produces Loki's own `404 page not found`.
- The frontend `npm test` must run from `ecom-frontend` (or with that workdir);
  from the repository root it fails with `Missing script: "test"`.
- Angular's default `HttpParams` codec leaves `=` unencoded in values. A query
  value containing a raw `=` (with `%7B...` already encoded) makes Spring Cloud
  Gateway's `containsEncodedParts()` treat the URI as unencoded and rebuild it
  with `build(false)`, double-encoding `%7B` to `%257B`; Loki then returns
  `400 ... unexpected %!(NOVERB)`. Use a strict `HttpParameterCodec`
  (`encodeURIComponent`) for LogQL query values. Measured: `query=%7Bservice=~...`
  is 400 through the gateway; `query=%7Bservice%3D~...` is 200, while Loki
  accepts both directly.
