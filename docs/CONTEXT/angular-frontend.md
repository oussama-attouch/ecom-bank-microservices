# Angular Frontend Conventions

## Stack
- Angular 19 (standalone components)
- PrimeNG 19 + @primeng/themes Aura
- Chart.js, @stomp/stompjs, angular-auth-oidc-client
- TypeScript 5.6

## Architecture
- Routing uses functional guards (`authGuard`, `roleGuard(['TELLER'])`).
- Services in `src/app/services`, components in `src/app/components`.
- Design system tokens in `_tokens.scss` (dark/light mode).

## Testing
- Karma/Jasmine specs colocated as `*.spec.ts`.
- Run `npm test` inside `ecom-frontend/`.
