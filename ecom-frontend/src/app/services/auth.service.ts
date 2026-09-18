import { Injectable, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Observable, BehaviorSubject, map, filter, take, tap, distinctUntilChanged, shareReplay } from 'rxjs';

@Injectable({ providedIn: 'root' })
export class AuthService {
  private oidcSecurityService = inject(OidcSecurityService);

  private userData: any = null;
  private userRolesSubject = new BehaviorSubject<string[]>([]);
  public userRoles$ = this.userRolesSubject.asObservable();

  /**
   * Built once and shared on purpose. This used to be a getter that returned a
   * fresh `pipe(map(...))` on every read; `*ngIf="isAuthenticated$ | async"`
   * therefore saw a new observable on every change-detection pass, causing the
   * async pipe to unsubscribe/resubscribe each time (leaked subscriptions and
   * NG0100 "Expression has changed after it was checked" on AppComponent).
   */
  readonly isAuthenticated$: Observable<boolean> = this.oidcSecurityService.isAuthenticated$.pipe(
    map((result) => result.isAuthenticated),
    distinctUntilChanged(),
    shareReplay({ bufferSize: 1, refCount: false })
  );

  constructor() {
    // userData$ returns the ID token payload (sub, preferred_username, name, email)
    this.oidcSecurityService.userData$.subscribe((result) => {
      this.userData = result.userData;
    });

    // realm_access.roles lives in the ACCESS token, not the ID token
    this.oidcSecurityService.getPayloadFromAccessToken(false).subscribe((payload: any) => {
      const roles = payload?.realm_access?.roles || [];
      this.userRolesSubject.next(roles);
    });
  }

  checkAuth(): Observable<any> {
    return this.oidcSecurityService.checkAuth().pipe(
      tap(() => {
        this.oidcSecurityService.getPayloadFromAccessToken(false).subscribe((payload: any) => {
          const roles = payload?.realm_access?.roles || [];
          if (roles.length > 0) {
            this.userRolesSubject.next(roles);
          }
        });
      })
    );
  }

  login(): void {
    this.oidcSecurityService.authorize();
  }

  logout(): void {
    this.userRolesSubject.next([]);
    this.oidcSecurityService.logoff().subscribe();
  }

  getUserName(): string {
    return (
      this.userData?.preferred_username ||
      this.userData?.name ||
      this.userData?.sub ||
      'User'
    );
  }

  getUserRoles(): string[] {
    return this.userRolesSubject.value;
  }

  hasRole(role: string): boolean {
    return this.getUserRoles().includes(role);
  }

  waitForRoles(): Observable<string[]> {
    return this.userRoles$.pipe(
      filter((roles) => roles.length > 0),
      take(1)
    );
  }
}
