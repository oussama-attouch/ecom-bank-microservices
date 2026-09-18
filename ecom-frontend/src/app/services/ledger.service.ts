import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Account, ChartSeries, KpiTrends } from '../models';
import { silentWhen } from './http-context.tokens';

@Injectable({ providedIn: 'root' })
export class LedgerService {
  private readonly base = '/ledger-service/api';

  constructor(private http: HttpClient) {}

  /** @param silent background poll — the caller renders the failure itself. */
  listAccounts(silent = false): Observable<Account[]> {
    return this.http.get<Account[]>(`${this.base}/accounts`, silentWhen(silent));
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

  /** @param silent background poll — the caller renders the failure itself. */
  listSagas(silent = false): Observable<any[]> {
    return this.http.get<any[]>(`${this.base}/sagas`, silentWhen(silent));
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
   */
  kpiTrends(range: string, silent = false): Observable<KpiTrends> {
    const token = (range || '').trim().toLowerCase();
    return this.http.get<KpiTrends>(`${this.base}/dashboard/kpi-trends?range=${token}`, silentWhen(silent));
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
   */
  getChartSeries(range: string, silent = true): Observable<ChartSeries> {
    const token = (range || '').trim().toLowerCase();
    return this.http.get<ChartSeries>(`${this.base}/dashboard/chart-series?range=${token}`, silentWhen(silent));
  }
}
