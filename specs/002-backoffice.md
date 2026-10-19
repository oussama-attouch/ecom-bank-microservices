# Feature: Backoffice — Manager access administration

## Business context

**Modules:** `gateway-service` (hosts the Keycloak Admin facade), `ecom-frontend`
(the page), `keycloak/realm-export.json` (a confidential client). No domain
service is touched.
**Role:** `MANAGER`, and only `MANAGER`. `TELLER` and `AUDITOR` are refused at
the edge.
**Problem:** the product has no way to administer access.

Today the three demo users exist only in `keycloak/realm-export.json`
(`teller: TELLER`, `manager: TELLER,MANAGER`, `auditor: AUDITOR`). Changing a
user's roles or cutting off a leaver requires one of:

1. the Keycloak admin console — outside the product, and the operator has to
   know the realm, the client and the console's own login; or
2. editing the realm export and re-importing, which per the documented sharp
   edge in `ARCHITECTURE.md` requires
   `docker-compose up -d --force-recreate keycloak` — `restart` does **not**
   re-import.

Neither is a backoffice. Access control is the highest-stakes action an
operations manager performs (granting `MANAGER`, disabling a teller), and it is
the one capability the platform cannot perform at all. Every role in the system
is currently assignable only from outside the system.

This is the first Backoffice feature and deliberately a narrow one: it manages
*who exists and what they may do*, not money. It therefore cannot corrupt the
ledger — the worst outcome of a bug here is an access decision, and the guards
below bound even that.

## Services impacted

- **`gateway-service`** — new `@ConditionalOnProperty`-gated
  `BackofficeUserController`, a `KeycloakAdminClient` (WebClient,
  client-credentials token cache), a `JwtAuthenticationConverter` that maps
  `realm_access.roles` to `ROLE_*` authorities, and one new `pathMatchers` rule
  in `SecurityConfig`.
- **`ecom-frontend`** — new `backoffice/users` route, page component, service,
  nav entry, `roleGuard(['MANAGER'])`, and one `proxy.conf.json` entry.
- **`keycloak/realm-export.json`** — new confidential client `ecom-backoffice`
  with service accounts enabled and `realm-management` client roles.
- Not impacted: `ledger-service`, `billing-service`, `customer-service`,
  `inventory-service`, `order-service`, `config-service`, `discovery-service`.
  No downstream call, no event, no schema.

## Expected behavior

### 1. Feature gate, default OFF

`backoffice.users.enabled=false` in `gateway-service`'s
`application.properties`. When the flag is off the controller bean is never
created, no handler mapping exists, and `/backoffice/users` returns **404** —
the route is absent rather than refused, matching `ProjectionAdminController`'s
reasoning in `ledger-service`.

The condition sits on the **class**, not on a method: Spring evaluates
`@Conditional*` while registering bean definitions, so a condition on a single
`@RequestMapping` method of an ordinary `@RestController` is never consulted by
`RequestMappingHandlerMapping` and silently does nothing.

Required configuration when the flag is on:

| Property | Default | Notes |
|---|---|---|
| `backoffice.keycloak.base-url` | `http://localhost:8180` | The same host as the existing `issuer-uri` |
| `backoffice.keycloak.realm` | `ecom-bank` | |
| `backoffice.keycloak.client-id` | `ecom-backoffice` | The confidential client |
| `backoffice.keycloak.client-secret` | — | No default. Blank + flag on ⇒ **startup fails** with a clear message |

A missing secret must fail the context rather than start a half-configured
admin surface: an admin endpoint that returns 500 on every call is worse than
one that refuses to boot, because the first looks like a bug and the second
looks like a misconfiguration.

### 2. Edge authorization: the first role rule at the gateway

`SecurityConfig` gains, **before** `.anyExchange().authenticated()`:

```java
.pathMatchers("/backoffice/**").hasRole("MANAGER")
```

`hasRole("MANAGER")` requires an authority of `ROLE_MANAGER`, and the gateway
currently has **no** `JwtAuthenticationConverter` — it reads `realm_access`
only inside `headerInjectionFilter` to build `X-User-Roles`. So the converter is
new and is part of this feature:

- read `realm_access.roles` from the JWT;
- map each to `new SimpleGrantedAuthority("ROLE_" + role)`, **upper-cased**;
- never lower-case, and never map anything else (no `scope` → authority
  conversion).

Two properties of this change are load-bearing:

1. **It must not alter authentication, only authorities.** Every existing
   route keeps `authenticated()`; only `/backoffice/**` narrows to a role.
   The `headerInjectionFilter` reads the token's claims, not its authorities, so
   `X-User-Roles` is unchanged.
2. **It must not break the WebSocket handshake.** The stream authenticates with
   `?access_token=` on an `Upgrade: websocket` request to
   `/ledger-service/ws/events` (see `SecurityConfig.bearerTokenConverter()`).
   That path must still match `authenticated()` and nothing narrower.

Unauthenticated → **401**. Authenticated without `MANAGER` (a teller, an
auditor) → **403**. A manager has `TELLER,MANAGER` in the demo realm and is
allowed.

### 3. Read: list users

`GET /backoffice/users?search=&first=0&max=20`

```json
{
  "total": 3,
  "first": 0,
  "max": 20,
  "users": [
    {
      "id": "0dbe6e2f-…",
      "username": "teller",
      "email": "teller@ecom-bank.local",
      "firstName": "Terry",
      "lastName": "Teller",
      "enabled": true,
      "roles": ["TELLER"]
    }
  ]
}
```

- `search` is passed straight to Keycloak's `search` parameter (case-insensitive
  substring over username, email, first and last name).
- `roles` is **filtered to the three realm roles**. Keycloak also holds
  `default-roles-ecom-bank`, `offline_access` and `uma_authorization` on every
  user; surfacing them would render the role editor meaningless and invite an
  operator to remove a composite role.
- **Implementation note — avoid the N+1.** Do not call
  `role-mappings/realm` once per user. Instead issue three calls,
  `GET /admin/realms/{realm}/roles/{TELLER|MANAGER|AUDITOR}/users?first=&max=`,
  and merge the results with the user page by id. Three upstream calls answer
  the whole page regardless of its size. A real deployment would cache; the demo
  realm has three users and does not need to.

Upstream: `GET /admin/realms/{realm}/users?search=&first=&max=`.

### 4. Write: replace a user's roles

`PUT /backoffice/users/{id}/roles` with `{ "roles": ["TELLER", "MANAGER"] }`

The body is the **complete desired set**, not a delta. The server computes the
assign/revoke difference and issues
`POST`/`DELETE /admin/realms/{realm}/users/{id}/role-mappings/realm`. A
full-set contract is idempotent and is what the UI already renders — three
checkboxes.

Validation and guards:

| Condition | Result |
|---|---|
| Any role outside `{TELLER, MANAGER, AUDITOR}` | 400, nothing changed |
| Target user does not exist | 404 |
| The caller removes `MANAGER` from **themselves** | 409 |
| The caller removes `MANAGER` from the **last enabled manager** | 409 |
| Success | 200 with the updated user object |

### 5. Write: enable / disable an account

`PUT /backoffice/users/{id}/enabled` with `{ "enabled": false }`

Upstream: `PUT /admin/realms/{realm}/users/{id}` with the `enabled` field only —
the facade must send a partial representation, not a full user object rebuilt
from the list response, or it will overwrite fields it never modelled.

| Condition | Result |
|---|---|
| The caller disables **themselves** | 409 |
| The caller disables the **last enabled manager** | 409 |
| Success | 200 with the updated user object |

### 6. Error mapping and secret hygiene

| Situation | Status | Body |
|---|---|---|
| Our validation fails | 400 | `{ "error": "<what>" }` |
| Caller is not a manager | 403 | gateway default |
| Keycloak returns 404 | 404 | `{ "error": "unknown user" }` |
| A guard above refuses | 409 | `{ "error": "<which guard>" }` |
| Keycloak 401/403 (wrong secret, missing service-account roles) | 502 | `{ "error": "identity provider rejected the administrator" }` |
| Keycloak unreachable / timeout | 502 / 504 | `{ "error": "identity provider unavailable" }` |

Never a 500 with a Keycloak body attached, and never a response that echoes the
admin token, the client secret or a raw Keycloak error payload. The 502 case
logs **one** ERROR naming the client id and the failing operation — the
operator's real problem is almost always "the realm import did not grant the
service-account roles", and the log line is the only place they will find out.

**Token handling.** `POST {base-url}/realms/{realm}/protocol/openid-connect/token`
with `grant_type=client_credentials`. Cache the access token in memory, refresh
30 s before `expires_in`, and single-flight the refresh so concurrent requests
do not stampede the token endpoint. The secret and the token are read from
configuration / held in memory only: never logged, never returned to the
browser, never placed in the frontend bundle.

### 7. UI

- Route, inside the authenticated shell:
  `{ path: 'backoffice/users', component: UserAdminComponent, canActivate: [roleGuard(['MANAGER'])] }`.
- Nav entry in `ecom-frontend/src/app/app.component.ts`, rendered **only** when
  `auth.hasRole('MANAGER')` — a teller must not see a link that 403s.
- `ecom-frontend/proxy.conf.json` gains a `/backoffice` entry targeting
  `http://localhost:8888`, with **no** `pathRewrite`, like every other prefix.
- The page: debounced search (300 ms), a table of username / name / email /
  role chips / enabled switch, a role editor with the three roles, a save
  action, a confirmation dialog before disabling, and error toasts.
- Three failure states must be visually distinct, because they mean different
  things to an operator: **404** → "the backoffice gate is disabled on the
  gateway" (not "no users"); **403** → "you do not hold MANAGER";
  empty list with 200 → "no users match".
- The page states the staleness window in copy, once, near the save button:
  *"Role and status changes apply at the user's next sign-in; an existing
  session stays valid for up to 15 minutes (`accessTokenLifespan 900`)."*

### 8. Known propagation delay

Disabling a user does not invalidate a token they already hold. Keycloak issues
a 15-minute access token, the gateway validates it statelessly against JWKS, and
no service is asked the user's current status. Session revocation is out of
scope (§Out of scope), so this delay is documented rather than fixed.

## Acceptance criteria

- [ ] With `backoffice.users.enabled` at its default (`false`),
      `GET /backoffice/users` returns **404** and no `BackofficeUserController`
      bean exists in the context.
- [ ] With the flag on and a blank
      `backoffice.keycloak.client-secret`, `gateway-service` **fails to start**
      with a message naming the missing property.
- [ ] A `manager` token lists the three demo users with exactly
      `TELLER` / `TELLER,MANAGER` / `AUDITOR` in `roles` — no
      `default-roles-ecom-bank`, `offline_access` or `uma_authorization`.
- [ ] The same call with a `teller` token returns **403**; with no token,
      **401**.
- [ ] A `PUT` adding `AUDITOR` to `teller` returns 200, and a subsequent `GET`
      shows `TELLER,AUDITOR`; the role appears in that user's next sign-in.
- [ ] A `PUT` with a role outside the three (e.g. `ADMIN`) returns **400** and
      changes nothing in Keycloak.
- [ ] `manager` removing `MANAGER` from themselves returns **409**;
      disabling themselves returns **409**.
- [ ] With only one enabled manager, demoting or disabling them returns **409**;
      with two enabled managers, demoting one succeeds.
- [ ] Disabling `teller` returns 200, and
      `GET /admin/realms/ecom-bank/users/{id}` with an admin token shows
      `enabled: false`.
- [ ] With Keycloak stopped, the endpoint answers 502/504 with a JSON error, logs
      exactly one ERROR naming the client id, and leaks neither the secret nor a
      token in the body or the log.
- [ ] `grep` of the built frontend bundle for the client secret finds nothing.
- [ ] Existing traffic is unchanged: dashboard KPIs, a transfer through the
      wizard, sagas, journal, and the live event stream — including its
      `?access_token=` WebSocket handshake — all still work.
- [ ] `mvn -pl gateway-service -am test` passes, and the frontend unit tests
      pass, with no new dependency.
- [ ] `README.md` (features + auth) and `ARCHITECTURE.md` §6 are updated in the
      same milestone.

## Constraints

- **Gateway is the only holder of the admin credential.** The browser never
  calls Keycloak's admin API, and no downstream service receives the secret.
  This is the same trust-boundary rule as `X-User-*` injection, applied to an
  outbound credential.
- **No new dependency.** `WebClient` is already on the gateway's classpath
  (reactive gateway stack). No `spring-boot-starter-security` is added to any
  downstream service, and none is touched.
- **`@ConditionalOnProperty` on the class, default `false`** — "absent, not
  idle". No `NewTopic`-style declaration for it, no fallback endpoint that
  answers 403 instead of 404.
- **The new path rule is narrower than, and listed before,
  `.anyExchange().authenticated()`.** Do not weaken the existing rule to make
  the new one easier to express.
- **`docker-compose.yml` is not modified** (`AGENTS.md`: only inside a milestone
  that names it). The realm change is `keycloak/realm-export.json` plus the
  documented `--force-recreate keycloak`.
- **The service-account roles must survive the realm import.** `ecom-backoffice`
  needs `serviceAccountsEnabled: true` and **four** `realm-management` client
  roles on its service-account user: `view-users`, `query-users`,
  `manage-users` and `view-realm`. `view-realm` is the non-obvious one — the
  `GET /admin/realms/{realm}/users` endpoint answers with `view-users`, but
  `GET /admin/realms/{realm}/roles/{role}/users` (the call that avoids the N+1
  in §3) answers **403** without it. Measured on Keycloak 26, not assumed.
  A Keycloak export/import that omits the service-account user's `clientRoles`
  produces a client that authenticates successfully and then 403s on every
  admin call — the failure is silent and looks like a code bug. Verify by
  listing the service account's effective roles after import.
- **No credentials in the UI, ever.** No password fields, no temporary
  passwords, no token display.
- **Flyway is not involved.** This feature persists nothing; Keycloak owns the
  only state. There is no schema change in any service.
- **Conventional commits, one per task**, e.g. `chore(keycloak):`,
  `feat(backoffice):`, `feat(frontend):`.

## Out of scope

- **Creating and deleting users.** No temporary passwords, no required actions,
  no email flows. Provisioning stays a Keycloak-console or realm-export
  operation; this feature governs users that already exist.
- **Password reset / credential management of any kind.**
- **Groups, composites, client roles beyond the three realm roles, client
  management, realm settings, identity providers, user federation.**
- **Session revocation.** Disabling an account does not kill a live session;
  §8 documents the 15-minute window instead of building revocation.
- **An audit table or admin-event UI.** Keycloak can record admin events; wiring
  them into the product is a separate spec. This feature logs mutations at INFO
  with the actor's `sub`, the target user and the change.
- **Four-eyes approval** for role changes. A manager acts alone; the guard set
  in §4–§5 bounds self-harm, not malfeasance.
- **Multi-realm, tenancy, rate limiting, pagination beyond `first`/`max`, CSV
  export, i18n.**
- **Any change to saga, ledger, billing or Kafka behavior.** This feature must
  not touch money.

## Design notes

**Why the facade lives in `gateway-service`.** The alternative is a ninth
`admin-service` module. The gateway is chosen because it is already the only
service that trusts and validates Keycloak (issuer-uri, JWKS) and already holds
the only credential-adjacent configuration, so adding an outbound admin
credential there does not create a second identity surface. A ninth module would
also ripple through `README.md` (8 microservices), `ARCHITECTURE.md`, the
verification appendix and the compose story for one screen. The cost is that the
gateway gains a small amount of business logic, which is the trade this project
already makes with `headerInjectionFilter`.

**Why a full role set rather than assign/revoke arrays.** Idempotent under
retry, matches the checkbox UI, and makes "the user ends in exactly this state"
the contract instead of "apply these deltas in this order".

**Accepted risks, stated rather than hidden.**

- The last-enabled-manager guard is **racy**: Keycloak's Admin API offers no
  transaction across the read and the write, so two managers demoting each
  other concurrently can still reach zero managers. A bank would enforce this
  with a database-backed invariant; this demo documents the window.
- The self-protection guards are per-request for the same reason.
- The realm-import fragility above is the most likely way this feature fails in
  a fresh clone, which is why it is called out in Constraints rather than left
  to discovery.
