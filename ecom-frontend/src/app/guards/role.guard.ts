import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { map, take } from 'rxjs';

export const roleGuard = (allowedRoles: string[]): CanActivateFn => {
  return () => {
    const authService = inject(AuthService);
    const router = inject(Router);

    return authService.waitForRoles().pipe(
      take(1),
      map((userRoles) => {
        const hasAccess = allowedRoles.some((r) => userRoles.includes(r));
        console.log('[RoleGuard]', { userRoles, allowedRoles, hasAccess });
        return hasAccess ? true : router.createUrlTree(['/dashboard']);
      })
    );
  };
};
