import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { AccountStatement, JournalEntry, TrialBalance } from '../models';
import { atParam } from './at-param';
import { silentWhen } from './http-context.tokens';

@Injectable({ providedIn: 'root' })
export class JournalService {
  private readonly base = '/ledger-service/api/journal';

  constructor(private http: HttpClient) {}

  /**
   * A page of the journal, newest first.
   *
   * @param transactionId narrows to one transaction's postings.
   * @param size page size.
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to read the journal as of; omit for live. The page is
   *   taken on the server <em>after</em> the time filter, so page 0 of a snapshot
   *   is the most recent postings as of the snapshot rather than the first page
   *   of the live journal with the newer rows removed.
   */
  entries(transactionId?: string, size = 200, silent = false, at?: Date | null): Observable<JournalEntry[]> {
    const params: string[] = [`size=${size}`];
    if (transactionId) params.push(`transactionId=${encodeURIComponent(transactionId)}`);
    const atQuery = atParam(at);
    if (atQuery) params.push(atQuery);
    return this.http.get<JournalEntry[]>(`${this.base}/entries?${params.join('&')}`, silentWhen(silent));
  }

  /**
   * The trial balance, as of {@code at} when it is given.
   *
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to sum the postings up to; omit for live. The ledger
   *   should read BALANCED at every instant, so this card staying green across a
   *   drag is itself the assertion that the history is internally consistent.
   */
  trialBalance(silent = false, at?: Date | null): Observable<TrialBalance> {
    const atQuery = atParam(at);
    const suffix = atQuery ? `?${atQuery}` : '';
    return this.http.get<TrialBalance>(`${this.base}/trial-balance${suffix}`, silentWhen(silent));
  }

  statement(accountId: string): Observable<AccountStatement> {
    return this.http.get<AccountStatement>(`${this.base}/account/${accountId}/statement`);
  }
}
