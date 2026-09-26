import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { filter, switchMap, take } from 'rxjs';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ButtonModule } from 'primeng/button';
import { AuthService } from '../../../services/auth.service';
import { BillingService } from '../../../services/billing.service';
import { ArchivedTransaction } from '../../../models';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../../shared/table-skeleton/table-skeleton.component';
import { KpiSkeletonComponent } from '../../shared/kpi-skeleton/kpi-skeleton.component';

/**
 * The archive of everything billing-service has consumed off the
 * `ledger-events` Kafka topic.
 *
 * <h2>Why the work happens here</h2>
 * `GET /api/archived-transactions` is `repository.findAll()` — no paging, no
 * filtering, no sorting. So the page loads the array once, and sorting,
 * filtering and paging are all client-side over that array. At archive scale
 * that is the wrong shape; at operator scale it is the honest one, and the
 * whole page then costs one request.
 *
 * <h2>Why this component asks for change detection explicitly</h2>
 * It is `OnPush`, and its data arrives from a subscription rather than from an
 * `async` pipe in the template — so nothing would otherwise mark the view dirty
 * and the resolved rows would never repaint over the skeletons. `OnPush` is kept
 * for the cheap render, and `markForCheck()` is called at each state change to
 * supply the signal the missing `async` pipe would have given.
 *
 * <h2>What the page is for</h2>
 * It is the pipeline's receipt. Live Events shows events moving; this shows
 * that they landed and stayed. A row here exists only if the ledger published,
 * Kafka delivered, and billing consumed and wrote it down.
 */
@Component({
  selector: 'app-archived-transactions',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    TableModule,
    TagModule,
    ButtonModule,
    EmptyStateComponent,
    TableSkeletonComponent,
    KpiSkeletonComponent
  ],
  templateUrl: './archived-transactions.component.html',
  styleUrl: './archived-transactions.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ArchivedTransactionsComponent implements OnInit {

  private readonly billing = inject(BillingService);
  private readonly auth = inject(AuthService);
  private readonly cdr = inject(ChangeDetectorRef);

  rows: ArchivedTransaction[] = [];

  typeFilter = '';
  accountFilter = '';
  dateFrom = '';
  dateTo = '';
  minAmount: number | null = null;
  maxAmount: number | null = null;

  /** True until the first archive response lands; drives the skeletons (brief 5.9). */
  loading = true;

  ngOnInit(): void {
    this.reload();
  }

  /**
   * Re-reads the archive. Also the Refresh button, which is why it is public.
   *
   * <h2>Why the request waits for a token</h2>
   * `authGuard` reads `isAuthenticated$` with `take(1)`, so on a hard refresh it
   * admits the route on the value restored from storage — before `checkAuth()`
   * has finished and an access token exists. A request fired from `ngOnInit`
   * therefore races the OIDC handshake: it leaves with no bearer token, comes
   * back 401, and a one-shot fetch has nothing left to try again with. The page
   * then sits on its skeleton forever, which is a lie — the archive was fine.
   *
   * So the fetch is chained behind `waitForRoles()`, the app's existing signal
   * that the token has arrived and been decoded (roles come out of the access
   * token, so a non-empty role list means the token is in hand). The skeleton
   * covers the wait either way, so nothing about the page changes when the token
   * was already there.
   */
  reload(): void {
    this.loading = true;
    this.cdr.markForCheck();
    this.auth.waitForRoles().pipe(
      take(1),
      switchMap(() => this.billing.listArchivedTransactions())
    ).subscribe({
      next: (rows) => {
        this.rows = rows ?? [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      // HTTP errors are surfaced once, globally, by error.interceptor.ts.
      error: (e) => {
        this.loading = false;
        this.cdr.markForCheck();
        console.error('[ArchivedTransactions] archive request failed', e);
      }
    });
  }

  get filteredRows(): ArchivedTransaction[] {
    const acct = this.accountFilter.trim().toLowerCase();
    return this.rows.filter((t) => {
      if (this.typeFilter && (t.type ?? '') !== this.typeFilter) return false;
      if (acct
        && !(t.fromAccountId ?? '').toLowerCase().includes(acct)
        && !(t.toAccountId ?? '').toLowerCase().includes(acct)) return false;
      // Compared as calendar days, in the operator's own zone: the ISO-8601
      // timestamp shares its prefix with the date input, so slicing is exact.
      const day = (t.timestamp ?? '').slice(0, 10);
      if (this.dateFrom && day < this.dateFrom) return false;
      if (this.dateTo && day > this.dateTo) return false;
      const amount = t.amount ?? 0;
      if (this.minAmount != null && amount < this.minAmount) return false;
      if (this.maxAmount != null && amount > this.maxAmount) return false;
      return true;
    });
  }

  get transferCount(): number {
    return this.rows.filter((t) => t.type === 'TRANSFER').length;
  }

  get totalVolume(): number {
    return this.rows.reduce((sum, t) => sum + (t.amount ?? 0), 0);
  }

  /** The most recent archive row, as a short local label — or a dash when empty. */
  get newestLabel(): string {
    let newest: string | undefined;
    for (const t of this.rows) {
      if (t.timestamp && (!newest || t.timestamp > newest)) newest = t.timestamp;
    }
    return newest ? formatStamp(newest) : '—';
  }

  clearFilters(): void {
    this.typeFilter = '';
    this.accountFilter = '';
    this.dateFrom = '';
    this.dateTo = '';
    this.minAmount = null;
    this.maxAmount = null;
  }

  short(id: string): string {
    return id.length > 12 ? id.substring(0, 12) + '…' : id;
  }

  typeSeverity(type?: string): 'info' | 'danger' | 'warn' | 'secondary' {
    switch (type) {
      case 'CREDIT': return 'info';
      case 'DEBIT': return 'danger';
      case 'TRANSFER': return 'warn';
      default: return 'secondary';
    }
  }

  /**
   * Exports what the table shows — same columns, same formatting, and only the
   * rows the filters left. Filter first, then export, and the file is the view.
   */
  exportCsv(): void {
    const header = ['ArchivedAt', 'TransactionId', 'Type', 'FromAccount', 'ToAccount', 'Amount'];
    const lines = this.filteredRows.map((t) =>
      [
        t.timestamp ? formatStamp(t.timestamp) : '',
        t.transactionId,
        t.type,
        t.fromAccountId,
        t.toAccountId,
        (t.amount ?? 0).toFixed(2)
      ]
        .map((v) => (v == null ? '' : `"${String(v).replace(/"/g, '""')}"`))
        .join(',')
    );
    const csv = [header.join(','), ...lines].join('\n');
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `archived-transactions-${new Date().toISOString().slice(0, 10)}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  }
}

/**
 * Formats an ISO-8601 instant the way the table shows it (`MMM d, y HH:mm:ss`,
 * in the reader's own zone) so the CSV carries the displayed value rather than
 * the raw string. Built from the local-time accessors, not `toISOString()`,
 * which would silently convert the value back to UTC.
 *
 * Returns the input unchanged if it will not parse, so a malformed timestamp
 * survives into the export instead of becoming "Invalid Date".
 */
function formatStamp(iso: string): string {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
                  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${MONTHS[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()} `
       + `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}
