# Dev proxy: direct-to-service routing (bypasses the gateway)

## Why this exists

In development, `ng serve` runs an Angular CLI **Vite** dev server, and its proxy uses
Node.js's HTTP client. That client is stricter than browsers and rejects a response
framing quirk produced by **Spring Cloud Gateway (Reactor Netty)**:

```
[vite] http proxy error: /ledger-service/api/accounts
Error: Parse Error: Data after `Connection: close`
```

The result: `POST` requests proxied *through the gateway* (`:8888`) intermittently fail
with an empty-body `500`, while `GET` requests and direct `curl`/browser calls work fine.

## What `proxy.conf.json` does

Rather than forwarding every request to the gateway (`http://localhost:8888`) and letting
it route by service name, the dev proxy now sends each service prefix **directly** to that
service's own port, using `pathRewrite` to strip the `/xxx-service` prefix:

| Prefix              | Target                  | Example → forwarded to |
|---------------------|-------------------------|------------------------|
| `/customer-service` | `http://localhost:8081` | `/customer-service/api/customers` → `http://localhost:8081/api/customers` |
| `/inventory-service`| `http://localhost:8082` | `/inventory-service/api/products` → `http://localhost:8082/api/products` |
| `/billing-service`  | `http://localhost:8083` | `/billing-service/bills` → `http://localhost:8083/bills` |
| `/order-service`    | `http://localhost:8084` | `/order-service/orders` → `http://localhost:8084/orders` |
| `/ledger-service`   | `http://localhost:8085` | `/ledger-service/api/accounts` → `http://localhost:8085/api/accounts` |

This is a **dev-only workaround**. It is safe because the frontend code keeps using the
service-name paths (`/customer-service/...`, `/ledger-service/...`, etc.), so no
application code changes — only the dev proxy routing changes.

## Production

In production, the Angular app is built (`ng build`) and served as static files. The
browser then talks to the **gateway directly at `http://localhost:8888/<service>/...`**
using the service-name paths, exactly as designed. Browsers handle the Netty chunked
responses correctly, so the gateway path works in production. Do **not** carry this
direct-port bypass into production.

## Testing the gateway path

To verify the gateway routing works (it does — this is purely a Node dev-proxy quirk),
use `curl` or Postman against the gateway:

```bash
curl http://localhost:8888/ledger-service/api/accounts
curl -X POST http://localhost:8888/ledger-service/api/transactions \
     -H "Content-Type: application/json" \
     -d '{"type":"CREDIT","accountId":"ACC-...","amount":100}'
```

Both `GET` and `POST` succeed against `:8888`; only Node's Vite dev proxy rejects the
Netty framing.
