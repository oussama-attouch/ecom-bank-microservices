/**
 * Deployment endpoints for the production build. Substituted for
 * `environment.ts` by the `fileReplacements` entry in `angular.json`'s
 * production configuration, so nothing imports this file directly.
 */
export const environment = {
  production: true,

  /**
   * The gateway, which is the only backend origin the browser talks to. It is
   * still left empty deliberately.
   *
   * API calls stay root-relative (`/ledger-service/api/journal`) and Vercel
   * proxies those prefixes to this gateway — see the `rewrites` in
   * `ecom-frontend/vercel.json`. Two reasons for proxying rather than pointing
   * the bundle straight at `https://ecom-gateway.onrender.com`:
   *
   *  - the gateway's `globalcors` allows exactly one origin, `http://localhost:4200`,
   *    so a cross-origin call from the Vercel domain is rejected by CORS; going
   *    through the same origin avoids the preflight entirely, and avoids having
   *    to widen the gateway's CORS policy to make a deployment work;
   *  - the live event stream is a WebSocket built from `location.host`, and a
   *    same-origin proxy is what lets `ws://`/`wss://` resolve to it.
   *
   * If the gateway is ever called directly, this becomes its origin and the
   * gateway's CORS policy has to allow the Vercel domain.
   */
  apiOrigin: '',

  /**
   * The deployed Keycloak. This is the one value that genuinely has to be
   * absolute: the browser is redirected here for login, so it cannot be a
   * relative path, and the gateway validates the same issuer.
   *
   * If `ecom-keycloak` is already taken on Render the hostname gets a suffix
   * and this constant, `render.yaml`'s EUREKA_DEFAULT_ZONE, and the realm's
   * redirect URIs all have to be updated together.
   */
  keycloakUrl: 'https://ecom-keycloak.onrender.com/realms/ecom-bank',

  keycloakClientId: 'ecom-frontend'
};
