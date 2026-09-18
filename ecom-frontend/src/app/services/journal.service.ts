import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AccountStatement, JournalEntry, TrialBalance } from '../models';
import { silentWhen } from './http-context.tokens';

@Injectable({ providedIn: 'root' })
export class JournalService {
  private readonly base = '/ledger-service/api/journal';

  constructor(private http: HttpClient) {}

  /** @param silent background poll — the caller renders the failure itself. */
  entries(transactionId?: string, size = 200, silent = false): Observable<JournalEntry[]> {
    const params: string[] = [`size=${size}`];
    if (transactionId) params.push(`transactionId=${encodeURIComponent(transactionId)}`);
    return this.http.get<JournalEntry[]>(`${this.base}/entries?${params.join('&')}`, silentWhen(silent));
  }

  /** @param silent background poll — the caller renders the failure itself. */
  trialBalance(silent = false): Observable<TrialBalance> {
    return this.http.get<TrialBalance>(`${this.base}/trial-balance`, silentWhen(silent));
  }

  statement(accountId: string): Observable<AccountStatement> {
    return this.http.get<AccountStatement>(`${this.base}/account/${accountId}/statement`);
  }
}
