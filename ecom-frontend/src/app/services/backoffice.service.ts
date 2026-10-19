import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { silentWhen } from './http-context.tokens';

/** A user as the backoffice API reports them — roles already filtered server-side. */
export interface BackofficeUser {
  id: string;
  username: string;
  email: string | null;
  firstName: string | null;
  lastName: string | null;
  enabled: boolean;
  roles: string[];
}

export interface BackofficeUserPage {
  total: number;
  first: number;
  max: number;
  users: BackofficeUser[];
}

/**
 * The gateway's backoffice API (`/backoffice/users`). Manager-only at the edge,
 * so a non-manager gets 403 before this service is reached.
 */
@Injectable({ providedIn: 'root' })
export class BackofficeService {
  private readonly base = '/backoffice/users';

  constructor(private http: HttpClient) {}

  /**
   * One page of users.
   *
   * @param silent the page renders the failure itself — "gate disabled" and
   *   "you are not a manager" need different words from the global
   *   "an unexpected error occurred" toast, so this call opts out of it.
   */
  list(search = '', first = 0, max = 50, silent = true): Observable<BackofficeUserPage> {
    const params: Record<string, string> = { first: String(first), max: String(max) };
    if (search) {
      params['search'] = search;
    }
    return this.http.get<BackofficeUserPage>(this.base, { params, ...silentWhen(silent) });
  }

  /** Replaces the user's managed roles with exactly `roles`. */
  replaceRoles(id: string, roles: string[]): Observable<BackofficeUser> {
    return this.http.put<BackofficeUser>(`${this.base}/${id}/roles`, { roles });
  }

  setEnabled(id: string, enabled: boolean): Observable<BackofficeUser> {
    return this.http.put<BackofficeUser>(`${this.base}/${id}/enabled`, { enabled });
  }
}
