# UI Audit — Backend Capability vs. Angular Frontend

**Date:** 2026-09-16
**Scope:** read-only audit. No code was changed.
**Method:** controllers and repositories read directly from source; every endpoint shape verified with live `curl` calls against the running stack (services on 8081–8085/8761/8888/9999, Keycloak 8180, `ng serve` on 4200).

---

## 1.1 — Backend API surface

### Infrastructure services (no business REST consumed by the UI)

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| discovery (8761) | ALL | `/eureka/**` | Eureka registry API | No (clients only) |
| discovery (8761) | GET | `/actuator/health` etc. | Actuator | No |
| config (9999) | GET | `/{application}/{profile}[/{label}]` | Config server (native backend → `config-repo/`) | No — consumed by Spring services only |
| config (9999) | GET | `/actuator/**` | Actuator | No |
| gateway (8888) | ALL | `/{service-id}/**` | Discovery-locator reverse proxy | **No — UI bypasses the gateway entirely (see §1.5)** |
| gateway (8888) | GET | `/actuator/**` | Actuator | No |

### customer-service (8081) — `spring.data.rest.base-path=/api`

`CustomerController` is **fully commented out**; all customer REST comes from Spring Data REST on `CustomerRepository`.

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| customer | GET | `/api/customers` | List (HAL: `_embedded.customers`, `page`) | ✅ `CustomerService.list/count` |
| customer | GET | `/api/customers/{id}` | Fetch one | ⚠️ only via gateway/Feign, not the UI |
| customer | POST | `/api/customers` | Create | ✅ `CustomerService.create` |
| customer | PUT/PATCH | `/api/customers/{id}` | Update | ✅ PUT via `CustomerService.update`; PATCH unused |
| customer | DELETE | `/api/customers/{id}` | Delete | ✅ `CustomerService.delete` |
| customer | GET | `/api/profile` | Data-REST metadata | No |
| customer | GET | `/testConfig1` | Config-server demo (`global.params.p1/p2`) | **No — BACKEND-ONLY** |
| customer | GET | `/testConfig2` | `CustomerConfigParams` bean | **No — BACKEND-ONLY** |

### inventory-service (8082) — `spring.data.rest.base-path=/api`

`ProductController` is **fully commented out**; all product REST comes from Spring Data REST on `ProductRepository`.

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| inventory | GET | `/api/products` | List (HAL: `_embedded.products`) | ✅ `ProductService.list/count` |
| inventory | GET | `/api/products/{id}` | Fetch one | ⚠️ Feign only |
| inventory | POST | `/api/products` | Create | ✅ `ProductService.create` |
| inventory | PUT/PATCH | `/api/products/{id}` | Update | ✅ PUT via `ProductService.update` |
| inventory | DELETE | `/api/products/{id}` | Delete | ✅ `ProductService.delete` |

### billing-service (8083) — `spring.data.rest.base-path=/api`

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| billing | GET | `/bills` | List bills (plain JSON, **Feign-enriched** with customer + products) | ✅ `BillingService.list/count` |
| billing | GET | `/bills/{id}` | Single enriched bill | **No — BACKEND-ONLY** |
| billing | POST | `/api/bills` | Create bill (Data REST) | ✅ `BillingService.generate` |
| billing | GET/PUT/PATCH/DELETE | `/api/bills[/{id}]` | Bill Data REST CRUD | No (POST only) |
| billing | GET | `/api/productItems` | Bill line items (Data REST) | **No — BACKEND-ONLY** |
| billing | POST | `/api/archived-transactions` | Ledger archive sink (HTTP publisher path) | No — machine-to-machine |
| billing | GET | `/api/archived-transactions` | **List archived ledger transactions** | **No — BACKEND-ONLY (7 rows currently exist)** |

### order-service (8084) — **no `spring.data.rest.base-path`** → Data REST is at the ROOT

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| order | GET | `/orders` | List (HAL: `_embedded.orders`) | ✅ `OrderService.list/count` |
| order | GET | `/orders/{id}` | Single order (bare entity, no customer) | No |
| order | POST | `/api`… no — `/orders` | Create order | ✅ `OrderService.create` |
| order | PUT/PATCH/DELETE | `/orders/{id}` | Update/delete | No |
| order | GET | `/orders/{id}/productItems` | Order line items | No |
| order | GET/POST | `/productItems` | Line items | ⚠️ `OrderService.addItem` exists but is **never called** (dead code) |
| order | GET | `/fullOrder/{id}` | Order + customer + product enrichment (Feign) | ✅ `OrderService.fullOrder` |
| order | GET | `/profile` | Data-REST metadata | No |

### ledger-service (8085)

| Service | Method | Path | Purpose | Used by frontend? |
|---|---|---|---|---|
| ledger | POST | `/api/accounts` | Create account `{customerId}` → `AccountState` (201) | ✅ `LedgerService.createAccount` |
| ledger | GET | `/api/accounts` | All accounts (`AccountState[]`) | ✅ `LedgerService.listAccounts` |
| ledger | GET | `/api/accounts/{id}/balance` | Replayed balance + full history | ✅ `LedgerService.balance` |
| ledger | GET | `/api/accounts/{id}/history` | **Identical payload to `/balance`** | ✅ `LedgerService.history` |
| ledger | POST | `/api/transactions` | CREDIT / DEBIT / TRANSFER (non-saga) | ✅ `LedgerService.credit/debit/transfer` |
| ledger | POST | `/api/transfers[?simulateFailure]` | **Saga transfer** (compensation) | ✅ `LedgerService.transferSaga` |
| ledger | GET | `/api/sagas` | All sagas | ✅ `LedgerService.listSagas` |
| ledger | GET | `/api/sagas/{transactionId}` | One saga + steps | ✅ `LedgerService.getSaga` |
| ledger | GET | `/api/journal/entries[?transactionId&page&size]` | Journal entries (default **size=20**) | ✅ `JournalService.entries` |
| ledger | GET | `/api/journal/trial-balance` | `{totalDebits,totalCredits,balanced}` | ✅ `JournalService.trialBalance` |
| ledger | GET | `/api/journal/account/{accountId}/statement` | Statement lines + running balance | ✅ `JournalService.statement` |
| ledger | POST | `/api/journal/test/inject-unbalanced` | Test helper: corrupt the ledger | **No — BACKEND-ONLY (test hook)** |

---

## 1.2 — Frontend consumption

| Frontend Service | Method | Endpoint Hit |
|---|---|---|
| `LedgerService` | `listAccounts()` | GET `/ledger-service/api/accounts` |
| `LedgerService` | `createAccount(customerId)` | POST `/ledger-service/api/accounts` |
| `LedgerService` | `credit(accountId, amount, description?)` | POST `/ledger-service/api/transactions` `{type:'CREDIT'}` |
| `LedgerService` | `debit(...)` | POST `/ledger-service/api/transactions` `{type:'DEBIT'}` |
| `LedgerService` | `transfer(from, to, amount)` | POST `/ledger-service/api/transactions` `{type:'TRANSFER'}` |
| `LedgerService` | `balance(accountId)` | GET `/ledger-service/api/accounts/{id}/balance` |
| `LedgerService` | `history(accountId)` | GET `/ledger-service/api/accounts/{id}/history` |
| `LedgerService` | `transferSaga(src,dst,amount,txId,simulate?)` | POST `/ledger-service/api/transfers[?simulateFailure=true]` |
| `LedgerService` | `getSaga(transactionId)` | GET `/ledger-service/api/sagas/{id}` |
| `LedgerService` | `listSagas()` | GET `/ledger-service/api/sagas` |
| `JournalService` | `entries(transactionId?, size=200)` | GET `/ledger-service/api/journal/entries?size=&transactionId=` |
| `JournalService` | `trialBalance()` | GET `/ledger-service/api/journal/trial-balance` |
| `JournalService` | `statement(accountId)` | GET `/ledger-service/api/journal/account/{id}/statement` |
| `CustomerService` | `list()` / `count()` | GET `/customer-service/api/customers` → `_embedded.customers` |
| `CustomerService` | `create(customer)` | POST `/customer-service/api/customers` |
| `CustomerService` | `update(id, customer)` | PUT `/customer-service/api/customers/{id}` |
| `CustomerService` | `delete(id)` | DELETE `/customer-service/api/customers/{id}` |
| `ProductService` | `list()` / `count()` | GET `/inventory-service/api/products` → `_embedded.products` |
| `ProductService` | `create/update/delete` | POST/PUT/DELETE `/inventory-service/api/products[/{id}]` |
| `OrderService` | `list()` / `count()` | GET `/order-service/orders` → `_embedded.orders` |
| `OrderService` | `create(order)` | POST `/order-service/orders` |
| `OrderService` | `fullOrder(id)` | GET `/order-service/fullOrder/{id}` |
| `OrderService` | `addItem(...)` | POST `/order-service/productItems` — **never called** |
| `BillingService` | `list()` / `count()` | GET `/billing-service/bills` |
| `BillingService` | `generate(customerId)` | POST `/billing-service/api/bills` |
| `AuthService` / OIDC | `checkAuth/login/logout` | Keycloak `http://localhost:8180/realms/ecom-bank` (discovery, token, JWKS) |

**Transport note:** every `/xxx-service/*` path is rewritten by `proxy.conf.json` to the service's **direct port**. Port 8888 (the gateway) appears **nowhere** in the frontend source.

---

## 1.3 — Gaps

### BACKEND-ONLY (exposed but never called by the UI)

| Endpoint | Notes |
|---|---|
| GET `/api/archived-transactions` | **7 archived transactions exist right now and have no screen at all.** This is the single most valuable unused endpoint — it is the Kafka/HTTP archival proof. |
| GET `/bills/{id}` | Enriched single bill; no bill detail screen. |
| GET `/api/productItems` (billing) | Bill line items; the bills table only shows a **count** of items, never the items. |
| GET `/orders/{id}`, GET `/orders/{id}/productItems` | Order detail exists only through `/fullOrder/{id}`. |
| PUT/PATCH/DELETE on orders, bills, product items | Data-REST write surface unused (no edit/delete order/bill UI). |
| GET `/api/customers/{id}`, GET `/api/products/{id}` | Only reachable via Feign today; the UI lists everything instead. |
| GET `/testConfig1`, GET `/testConfig2` (customer 8081) | Config-server demo endpoints; nothing surfaces them. |
| POST `/api/journal/test/inject-unbalanced` | Deliberate corruption hook for the integrity checker — arguably should stay hidden, but it is undocumented in the UI. |
| The entire gateway (8888) route surface | The UI never routes through it. |

### FRONTEND-BROKEN (calls succeed but the UI renders poorly / cannot render the data)

| # | Symptom | Detail |
|---|---|---|
| B1 | **Orders table shows a raw ISO string** in "Created" | Template binds `{{ o.createdAt }}` (no `date` pipe). API returns `"createdAt":"2026-09-16T02:06:14.129+00:00"`. |
| B2 | **Bills table shows a raw ISO string** in "Date" | Template binds `{{ b.billingDate }}` (no pipe). API returns `"billingDate":"2026-09-16T02:06:16.867+00:00"`. |
| B3 | **Banking account-history table has no date column** | Only Offset / Type / Amount / Description are rendered, although every event carries `occurredAt`. |
| B4 | **Banking history "Amount" is blank for `ACCOUNT_CREATED`** | That event type has no `amount` field; the cell renders empty rather than "—". |
| B5 | **Dashboard "Transaction Feed" has no status column** | Journal entries carry **no `status`** field at all (see §1.4-B), so `COMPLETED/COMPENSATING/FAILED` can never appear there without joining to `/api/sagas` by `transactionId`. |
| B6 | **Banking Credit/Debit buttons send the type as the description** | `ledger.credit(id, amount, type)` passes `'CREDIT'`/`'DEBIT'` as `description` → journal shows "CREDIT" instead of a real description. |
| B7 | **Orders table "Customer" column shows a numeric id** | `{{ o.customerId }}`; the list payload has no `customer` (only `/fullOrder/{id}` enriches it). |
| B8 | **Dashboard silently blanks on any single API failure** | `forkJoin` in `refreshAll()` has **no error callback**; one failing call (e.g. ledger down) wipes KPIs, charts, trial balance and the feed with no message. |
| B9 | **Account statement for an unknown account looks "successful"** | `/api/journal/account/{id}/statement` returns **HTTP 200** with `lines: []` even when the account does not exist, whereas `/api/accounts/{id}/balance` returns 404. The statement screen therefore cannot tell "no activity" from "no such account". |
| B10 | **`OrderService.addItem()` is dead code** | Declared, never referenced by any component. |

### FRONTEND-MISSING (backend capability with no screen)

| # | Missing screen | Backend support |
|---|---|---|
| M1 | **Notification centre** | Nothing exists (see §1.4-D). The bell is decorative. |
| M2 | **Archived transactions / Kafka archival view** | GET `/api/archived-transactions` (7 rows). Would demonstrate the exactly-once Kafka pipeline end-to-end. |
| M3 | **Bill detail (line items)** | GET `/bills/{id}` / `/api/productItems` — the UI only shows an item count. |
| M4 | **Order line-item management** | `POST /productItems` + `addItem()` exist; no UI adds items to an order. |
| M5 | **Saga → transaction replay from the list** | `/api/sagas` is listed and the inspector has "Replay", but there is no cross-link from the **journal/archived transaction** back to its saga (the feed's txn id is plain text). |
| M6 | **Trial-balance / integrity detail page** | `POST /api/journal/test/inject-unbalanced` and the startup `LedgerIntegrityChecker` have no UI surface; the dashboard only shows the boolean. |
| M7 | **Config/ops introspection** | `/testConfig1`, `/testConfig2`, `/actuator/*` are unreachable from the UI. |

---

## 1.4 — Specific bug hunt

### A. Transaction dates

**Dashboard — "Transaction Feed", Timestamp column**
- Template binding: `dashboard.component.ts:101` → `<td>{{ e.createdAt | date:'HH:mm:ss' }}</td>`
- Source collection: `updateFeed(entries)` where `entries = this.journal.entries()` (journal entries, reversed, top 20)
- Live API field: **`createdAt`** — `"createdAt":"2026-09-16T03:27:39.586877Z"`
- **Verdict: field names MATCH and the binding is correct.** No mismatch, no raw string.

**Journal Explorer — "Time" column**
- Template binding: `journal-explorer.component.ts:54` → `<td>{{ e.createdAt | date:'MMM d, y HH:mm:ss' }}</td>`
- Live API field: **`createdAt`** (same payload as above)
- **Verdict: correct.**

**Timestamp parsing is NOT the problem.** The API emits 6- and 9-digit fractional seconds (e.g. `.586877Z`, `.860703100Z`). I tested V8 (Node 22, same engine as Chrome/Edge) directly:

```
OK   2026-09-16T03:27:39.586877Z    -> 2026-09-16T03:27:39.586Z
OK   2026-09-16T03:27:36.860703100Z -> 2026-09-16T03:27:36.860Z
```
So `Date` truncates the extra digits rather than returning `Invalid Date`. (Note: Safari's stricter ISO parser is a residual risk for that format, but it is not what is happening on Chromium.)

**The real date defects are elsewhere:** B1 (orders, raw string), B2 (bills, raw string), B3 (account history, no date column at all). The dashboard/journal-explorer tables that the brief named are, in fact, correct — if the user is seeing blanks there, the likely cause is **B8**: the dashboard's `forkJoin` has no error handler, so one failing request leaves `feed` empty and the whole Command Center unpopulated.

### B. Transaction statuses

- **Saga list — correct.** `saga-list.component.ts:54` binds `<p-tag [value]="s.status" [severity]="severity(s.status)">` and the API returns `"status":"COMPLETED" | "COMPENSATING" | "FAILED"`. `severity()` maps them to success/warn/danger. Verified live (3 sagas: 1 COMPLETED, 2 COMPENSATING).
- **Transaction Feed — cannot work as written.** The feed renders **journal entries**, whose API shape is:
  ```json
  {"id":"…","transactionId":"…","debitAccountId":"CASH_ACCOUNT","creditAccountId":"ACC-1DB49304",
   "amount":5000.0,"currency":"USD","description":"initial deposit",
   "createdAt":"2026-09-16T03:27:39.586877Z","postedBy":"SYSTEM","valid":true}
  ```
  There is **no `status` field** and no `p-tag` in the feed template. Saga status lives only on `/api/sagas` (`status` + `steps[].status`). To show coloured badges in the feed the UI must **join on `transactionId`** (e.g. build a `Map<transactionId, status>` from `ledger.listSagas()`, which the dashboard **already fetches** in the same `forkJoin` but never uses for the feed).
- Also note: journal `description` values are internal literals (`"TRANSFER debit"`, `"COMPENSATION (reverse debit)"`, `"initial deposit"`), not user-facing text.

### C. Account details ("eye" icon on Banking)

**Trace:** `banking.component.ts:54` → `selectAccount(a.accountId)` → `forkJoin({bal: ledger.balance(id), hist: ledger.history(id)})` → `this.detail = {...hist, balance: bal.balance}` → renders `*ngIf="detail"` panel with a history `p-table`.

**Live verification of both calls (account `ACC-1DB49304`):**

| Call | Result |
|---|---|
| GET `/api/accounts/ACC-1DB49304/balance` | **200** — `{accountId, customerId:4, holderName:"Alice", balance:4500.0, history:[…7 events…]}` |
| GET `/api/accounts/ACC-1DB49304/history` | **200** — **byte-for-byte the same payload** |
| Proxy path via 4200 | 200 |

**Verdict: I could not reproduce a hard failure in the data path.** Both endpoints return the shape the component expects, the spread keeps `history`, and every rendered field (`offset`, `type`, `amount`, `description`) exists on the event objects (`type` is the Jackson discriminator: `ACCOUNT_CREATED|MONEY_CREDITED|MONEY_DEBITED`).

**Most likely cause of what the user saw — a stale account id.** If the id no longer exists, `/balance` returns:
```
HTTP 404  {"timestamp":"2026-09-16T04:33:42.321+00:00","status":404,"error":"Not Found",
           "path":"/api/accounts/ACC-DOESNOTEXIST/balance"}
```
Because the two calls are combined with `forkJoin`, **one 404 kills both** → `detail` stays `null` → the panel never appears and only a raw error toast shows. This is very easy to hit after any ledger restart with the in-memory store (`--spring.profiles.active=inmem`) or after a DB reset, because `listAccounts()` still returns rows the old session had cached.

**Genuine defects in this component regardless of the 404:** B3 (no date column), B4 (blank Amount for `ACCOUNT_CREATED`), B6 (type used as description), plus the **redundant identical double fetch** — `/balance` and `/history` return the same document, so one call suffices.

### D. Notification component ("bell" in the topbar)

- The bell exists at `app.component.html:31`:
  ```html
  <button pButton icon="pi pi-bell" class="p-button-text p-button-rounded" aria-label="Notifications"></button>
  ```
- It has **no `(click)` handler, no `p-overlaypanel`/`p-menu`/`p-tieredmenu`, no badge, and no bound state.**
- A repo-wide grep for `notification|bell|Notific` across `ecom-frontend/src` returns **exactly one hit — that button line.**
- There is **no `NotificationService`, no notification model, no dropdown component.** Nothing was left half-wired; it was never built.
- **Verdict: decorative only. M1 is a from-scratch feature, not a repair.**

---

## 1.5 — Cross-cutting issues

**Rendering defects visible in tables**

| Where | Problem |
|---|---|
| Orders → Created | Raw ISO string `2026-09-16T02:06:14.129+00:00` (B1) |
| Bills → Date | Raw ISO string `2026-09-16T02:06:16.867+00:00` (B2) |
| Orders → Customer | Numeric `customerId`, not a name (B7) |
| Banking → history Amount | Empty cell for `ACCOUNT_CREATED` (B4) |
| Banking → history | No timestamp column despite `occurredAt` being present (B3) |
| Bills → Items | Only a count badge; line items are never shown (M3) |
| Dashboard → Feed | Transaction id is plain text, not a link to the saga (M5) |

No `[object Object]` or literal `undefined` interpolations were found — the HAL unwrapping in `customer/product/order` services (`r?._embedded?.x ?? []`) is correct, and the bills/orders tables use safe navigation (`o.customer?.name ?? o.customerId`).

**Errors / console**

I cannot read the browser console from here. What I *can* state from the code and live probes:

1. **Every frontend data call returns 200 today** through the dev proxy (customers, products, orders, bills, accounts, sagas all verified).
2. **No 500s are reachable from the UI right now.** The only 5xx I could produce earlier in this project came from the gateway, and the UI never talks to the gateway.
3. **Error handling is now global but verbose-averse:** `error.interceptor.ts` maps status → friendly text and **re-throws**, so components that have their own `error:` callbacks (`banking`, `orders`, `bills`, `transactions`) still add a *second* toast. Components without one (`dashboard`, `saga-list`, `journal-explorer`, `statement`, `saga-inspector` on the list load) rely solely on the interceptor. The dashboard's missing handler is the dangerous case (B8).
4. **The 900-second Keycloak access token** is attached by `auth.interceptor.ts` to every request, but since all calls go to **direct service ports via the proxy**, no service validates it. A token refresh/expiry therefore has no visible effect on data loading — the JWT layer is effectively bypassed in dev.

**Architectural finding — the security layer is bypassed by the UI**

`proxy.conf.json` rewrites `/customer-service`, `/inventory-service`, `/billing-service`, `/order-service`, `/ledger-service` straight to ports 8081–8085. Consequently:

- The **gateway (8888) is never exercised** by the Angular app, so its JWT validation and `X-User-Roles` header injection are untested by the UI.
- Any **role-based backend authorization** would not be enforced on these paths.
- The UI *does* enforce roles client-side (`roleGuard`, `hasRole('AUDITOR')`, the $10K teller limit), but purely cosmetically — a user could call the direct ports.
- This also means `README-DEV-PROXY.md`'s gateway-bypass workaround remains load-bearing, not just a convenience.

**Other observations**

- `/api/journal/entries` paginates with a **default `size=20`** server-side; the UI compensates with `size=200`, so a ledger with >200 entries will silently truncate the Journal Explorer, the dashboard feed and the KPIs.
- `/api/accounts/{id}/balance` and `/history` are duplicates — one is redundant.
- `POST /api/journal/test/inject-unbalanced` is a live, unauthenticated (direct-port) way to corrupt the ledger from any client; worth reconsidering.
- Billing `/bills` returns **already-Feign-enriched** data; if `customer-service` or `inventory-service` is down, this endpoint degrades (earlier in the project it 500'd for that reason). The UI has no special handling.

---

## Prioritised shortlist for Phase 2

1. **B8** dashboard `forkJoin` error handling (blank Command Center — highest user-visible impact).
2. **B1 + B2 + B3** date rendering (raw ISO in Orders/Bills; missing date in account history).
3. **B5** feed status badges via a `transactionId → saga.status` join (data is already fetched).
4. **C** account-details robustness: single call instead of two, graceful 404, and the B3/B4/B6 fixes.
5. **M2** archived-transactions screen (7 rows of real data, proves the Kafka pipeline).
6. **B7, M3, M4** enrichment gaps (customer names on orders, bill/order line items).
7. **D / M1** notification centre (net-new feature).
8. **B9** statement 404-vs-empty ambiguity, **B10** dead `addItem()`, **M6** integrity UI.
9. **Architectural**: decide whether the UI should route through the gateway (re-enabling JWT + roles) or keep the direct-port bypass — this decision affects the visual-modernisation phase.
