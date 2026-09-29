import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { switchMap, take } from 'rxjs';

import { environment } from '../../environments/environment';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const oidcSecurityService = inject(OidcSecurityService);

  // The identity provider is not behind the gateway and does not accept the
  // access token as a bearer credential, so attaching one to its own endpoints
  // is at best pointless and at worst a token sent to a service that logs it.
  // Matched against the configured authority rather than a literal host, so
  // this keeps working when the production build swaps in the deployed realm.
  if (req.url.includes('/assets/') || req.url.startsWith(environment.keycloakUrl)) {
    return next(req);
  }

  return oidcSecurityService.getAccessToken().pipe(
    take(1),
    switchMap((token) => {
      if (token) {
        const authReq = req.clone({
          setHeaders: {
            Authorization: `Bearer ${token}`
          }
        });
        return next(authReq);
      }
      return next(req);
    })
  );
};
