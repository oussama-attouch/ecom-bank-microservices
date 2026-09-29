import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideAnimationsAsync } from '@angular/platform-browser/animations/async';
import { providePrimeNG } from 'primeng/config';
import { ConfirmationService, MessageService } from 'primeng/api';
import Aura from '@primeng/themes/aura';
import { provideAuth } from 'angular-auth-oidc-client';

import { routes } from './app.routes';
import { loadingInterceptor } from './services/loading.interceptor';
import { errorInterceptor } from './services/error.interceptor';
import { authInterceptor } from './interceptors/auth.interceptor';
import { environment } from '../environments/environment';

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor, loadingInterceptor, errorInterceptor])),
    provideAnimationsAsync(),
    providePrimeNG({
      theme: {
        preset: Aura,
        options: { darkModeSelector: '.app-dark' }
      }
    }),
    provideAuth({
      config: {
        // From src/environments/environment.ts, which the production build
        // swaps for environment.prod.ts (see fileReplacements in angular.json).
        // It has to be per-environment: this is where the browser is redirected
        // to log in, and the deployed realm is a different origin from the
        // compose container on :8180.
        authority: environment.keycloakUrl,
        redirectUrl: window.location.origin + '/callback',
        postLogoutRedirectUri: window.location.origin,
        clientId: environment.keycloakClientId,
        scope: 'openid profile email',
        responseType: 'code',
        silentRenew: true,
        useRefreshToken: true
      }
    }),
    MessageService,
    ConfirmationService
  ]
};
