/**
 * Deployment endpoints, per environment.
 *
 * This file is the local-development default and is swapped out at build time:
 * `angular.json`'s production configuration has a `fileReplacements` entry that
 * replaces it with `environment.prod.ts`. That is why neither file imports the
 * other and why both must declare the same shape — the compiler only ever sees
 * one of them, so a field added here and forgotten there is a build error in
 * one configuration rather than a silent difference in behaviour.
 *
 * `apiOrigin` is only consumed by `environment.prod.ts`'s callers; see the note
 * there for why the API is reached through the page's own origin.
 */
export const environment = {
  production: false,

  /**
   * Empty: every API call in this app is a root-relative path such as
   * `/ledger-service/api`, resolved by the dev server through
   * `proxy.conf.json` (which forwards to the gateway on :8888 and strips the
   * service prefix). An empty origin means "same origin", so the string
   * concatenation in callers is a no-op here.
   */
  apiOrigin: '',

  /** Keycloak realm URL. Local dev runs the compose container on :8180. */
  keycloakUrl: 'http://localhost:8180/realms/ecom-bank',

  keycloakClientId: 'ecom-frontend'
};
