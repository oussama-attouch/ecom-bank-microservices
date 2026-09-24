import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Account, ChartSeries, KpiTrends, ProjectionRebuildReport } from '../models';
import { atParam, query } from './at-param';
import { silentWhen } from './http-context.tokens';

@Injectable({ providedIn: 'root' })
export class LedgerService {
  private readonly base = '/ledger-service/api';

  constructor(private http: HttpClient) {}

  /**
   * Every account.
   *
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to read as of; omit for live. Under a snapshot this is
   *   the accounts as they stood then, which is also the source of the browser's
   *   own Assets Under Management and Active Accounts cards, so those two follow
   *   the scrubber through this one call.
   */
  listAccounts(silent = false, at?: Date | null): Observable<Account[]> {
    return this.http.get<Account[]>(
      `${this.base}/accounts${query(atParam(at))}`, silentWhen(silent));
  }

  createAccount(customerId: number): Observable<Account> {
    return this.http.post<Account>(`${this.base}/accounts`, { customerId });
  }

  credit(accountId: string, amount: number, description?: string): Observable<any> {
    return this.http.post(`${this.base}/transactions`, { type: 'CREDIT', accountId, amount, description });
  }

  debit(accountId: string, amount: number, description?: string): Observable<any> {
    return this.http.post(`${this.base}/transactions`, { type: 'DEBIT', accountId, amount, description });
  }

  transfer(fromAccountId: string, toAccountId: string, amount: number): Observable<any> {
    return this.http.post(`${this.base}/transactions`, { type: 'TRANSFER', fromAccountId, toAccountId, amount });
  }

  /** @param silent caller renders the failure itself (no global error toast). */
  balance(accountId: string, silent = false): Observable<Account> {
    return this.http.get<Account>(`${this.base}/accounts/${accountId}/balance`, silentWhen(silent));
  }

  /** @param silent caller renders the failure itself (no global error toast). */
  history(accountId: string, silent = false): Observable<Account> {
    return this.http.get<Account>(`${this.base}/accounts/${accountId}/history`, silentWhen(silent));
  }

  transferSaga(sourceAccountId: string, destinationAccountId: string, amount: number, transactionId: string, simulateFailure = false): Observable<any> {
    const q = simulateFailure ? '?simulateFailure=true' : '';
    return this.http.post(`${this.base}/transfers${q}`, { sourceAccountId, destinationAccountId, amount, transactionId });
  }

  /** @param silent background poll — the caller renders the failure itself. */
  getSaga(transactionId: string, silent = false): Observable<any> {
    return this.http.get(`${this.base}/sagas/${transactionId}`, silentWhen(silent));
  }

  /**
   * The saga list.
   *
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to read as of; omit for live. The rows carry the
   *   sagas' current statuses — the server stores no history of them — so a
   *   snapshot shows which sagas had started by then and how they stand now.
   */
  listSagas(silent = false, at?: Date | null): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/sagas${query(atParam(at))}`, silentWhen(silent));
  }

  /**
   * Current vs previous value for each Command Center KPI, over one selected
   * range, with the delta and the sparkline series behind each card.
   *
   * The range is normalised to lower case here for the same reason the chart
   * series are: the server's tokens are lower case (`7d`, `30d`, `90d`, `1y`,
   * `all`) and it rejects anything else with a 400, while the header selector
   * spells them `7D`, `30D`, … for display.
   *
   * @param range  one of 7d, 30d, 90d, 1y, all (case-insensitive).
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to anchor the whole grid on; omit for live. The range
   *   still applies: it selects the window measured back from that instant, and
   *   the equal window before it that every delta compares against.
   */
  kpiTrends(range: string, silent = false, at?: Date | null): Observable<KpiTrends> {
    const token = (range || '').trim().toLowerCase();
    return this.http.get<KpiTrends>(
      `${this.base}/dashboard/kpi-trends${query(`range=${token}`, atParam(at))}`, silentWhen(silent));
  }

  /**
   * Everything the Command Center's four charts draw, for one selected range.
   *
   * The range is normalised to lower case here because the server's tokens are
   * lower case (`7d`, `30d`, `90d`, `1y`, `all`) and it rejects anything else
   * with a 400 — the header selector spells them `7D`, `30D`, … for display.
   *
   * @param range  one of 7d, 30d, 90d, 1y, all (case-insensitive).
   * @param silent background poll — the caller renders the failure itself.
   * @param at the instant to draw the series as of; omit for live. All four
   *   panels follow it, including the balance distribution and the saga
   *   breakdown, which are all-time reads in the live form.
   */
  getChartSeries(range: string, silent = true, at?: Date | null): Observable<ChartSeries> {
    const token = (range || '').trim().toLowerCase();
    return this.http.get<ChartSeries>(
      `${this.base}/dashboard/chart-series${query(`range=${token}`, atParam(at))}`, silentWhen(silent));
  }

  /**
   * Replay the event log and check the read models against it.
   *
   * A single blocking request: the server folds the whole log and answers once,
   * so there is no progress to report while it runs and the caller shows an
   * indeterminate indicator rather than a percentage. The timeout is the proxy's
   * 60s for `/ledger-service`, which is far above the couple of seconds the
   * portfolio ledger takes.
   *
   * The failure is `silent` — the caller renders it, and it is worth rendering
   * specifically: this route is gated off by default, so the likeliest error is a
   * 404 meaning "not enabled" rather than "broken", and the operator needs to be
   * told which.
   */
  rebuildProjections(): Observable<ProjectionRebuildReport> {
    return this.http.post<ProjectionRebuildReport>(
      `${this.base}/admin/projections/rebuild`, {}, silentWhen(true));
  }
}
