import { Component, DestroyRef, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import {
  Observable, Subject, catchError, filter, fromEvent, interval, map, merge, of, startWith, switchMap
} from 'rxjs';
import { LedgerService, OutboxEvent, OutboxStatusResponse } from '../../../services/ledger.service';
import { EmptyStateComponent } from '../../shared/empty-state/empty-state.component';
import { TableSkeletonComponent } from '../../shared/table-skeleton/table-skeleton.component';

/**
 * Observability → Outbox: what the ledger has committed but not yet published.
 *
 * <p>The page is a window onto the relay, not a control panel for it, with one
 * exception: **Force Poll** runs a batch now instead of waiting for the next tick,
 * which is what makes the demo deterministic. Everything else is read-only, and
 * the counters are the authority — `events` is only the newest fifty rows, so a
 * card computed from the array would be a lie the moment the table overflows.
 *
 * <p>Auto-refresh is 5s, pausing while the tab is hidden: a hidden tab polling a
 * manager-only admin endpoint every five seconds forever is a request nobody
 * reads. It refreshes immediately on becoming visible again, so coming back to
 * the page never shows a stale queue for a whole tick.
 *
 * <p>Three failures mean three different things and are rendered as page states,
 * not toasts: 404 is "the outbox gate is off on the ledger service", 403 is "this
 * session is not a manager" (a stale token outlives a role change by up to 15
 * minutes), and anything else keeps the last good table on screen marked stale —
 * an outage should not erase the operator's view of the queue.
 */
@Component({
  selector: 'app-outbox',
  standalone: true,
  imports: [
    CommonModule,
    TableModule,
    ButtonModule,
    TagModule,
    TooltipModule,
    EmptyStateComponent,
    TableSkeletonComponent
  ],
  templateUrl: './outbox.component.html',
  styleUrl: './outbox.component.scss'
})
export class OutboxComponent implements OnInit {

  /** How often the page re-reads while it is visible. */
  private static readonly REFRESH_MS = 5_000;

  private readonly svc = inject(LedgerService);
  private readonly msg = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);

  /** Last good response; kept on screen when a refresh fails. */
  readonly status = signal<OutboxStatusResponse | null>(null);
  readonly loading = signal(true);

  /** Non-null when there is nothing to show: gate off (404) or not a manager (403). */
  readonly gateError = signal<string | null>(null);

  /** True when the newest refresh failed but the table still holds the last good one. */
  readonly stale = signal(false);

  /** Disables Force Poll while its own request is in flight. */
  readonly polling = signal(false);

  /** Manual refresh: the button, and the refresh after a forced poll. */
  private readonly refresh$ = new Subject<void>();

  ngOnInit(): void {
    const tabIsVisible = () => document.visibilityState === 'visible';

    merge(
      this.refresh$,
      interval(OutboxComponent.REFRESH_MS).pipe(filter(tabIsVisible)),
      fromEvent(document, 'visibilitychange').pipe(filter(tabIsVisible), map(() => void 0))
    ).pipe(
      // The initial read goes through the same path as every later one, so there
      // is one place that decides what a success or a failure does to the page.
      startWith(0),
      // switchMap, not mergeMap: a slow response must not overwrite a newer one.
      switchMap(() => this.fetch()),
      takeUntilDestroyed(this.destroyRef)
    ).subscribe();
  }

  /** Triggers a refresh without waiting for the next tick. */
  refresh(): void {
    this.refresh$.next();
  }

  /**
   * Runs one batch now. The toast reports what was published, and `processed: 0`
   * is honest rather than an error — the scheduled tick may hold the relay.
   */
  forcePoll(): void {
    if (this.polling()) {
      return;
    }
    this.polling.set(true);
    this.svc.triggerOutboxPoll().subscribe({
      next: (result) => {
        this.polling.set(false);
        this.msg.add({
          severity: 'success',
          summary: 'Poll complete',
          detail: `${result.processed} event${result.processed === 1 ? '' : 's'} published`,
          life: 4000
        });
        this.refresh();
      },
      error: (err: HttpErrorResponse) => {
        this.polling.set(false);
        this.msg.add({
          severity: 'error',
          summary: 'Poll failed',
          detail: this.describeFailure(err),
          life: 5000
        });
      }
    });
  }

  // ------------------------------------------------------------------
  // Row rendering
  // ------------------------------------------------------------------

  /** The first eight characters: enough to recognise a row, short enough to scan. */
  shortId(value: string): string {
    return value.length > 8 ? `${value.slice(0, 8)}…` : value;
  }

  /**
   * Badge severity per status. `PARKED` is deliberately not red: the relay did
   * the right thing — it stopped retrying an event that will not go through — and
   * colouring it like FAILED would hide the difference.
   */
  statusSeverity(status: OutboxEvent['status']): 'success' | 'warn' | 'danger' | 'secondary' {
    switch (status) {
      case 'PUBLISHED': return 'success';
      case 'PENDING': return 'warn';
      case 'FAILED': return 'danger';
      default: return 'secondary';
    }
  }

  // ------------------------------------------------------------------
  // Loading
  // ------------------------------------------------------------------

  private fetch(): Observable<void> {
    return this.svc.getOutboxStatus().pipe(
      map((response) => {
        this.status.set(response);
        this.gateError.set(null);
        this.stale.set(false);
        this.loading.set(false);
      }),
      catchError((err: HttpErrorResponse) => {
        this.loading.set(false);
        const message = this.describeFailure(err);
        if (err.status === 404 || err.status === 403) {
          // The endpoint is absent or refused: there is no data to keep showing.
          this.status.set(null);
          this.gateError.set(message);
          this.stale.set(false);
        } else if (this.status()) {
          this.stale.set(true);
        } else {
          this.gateError.set(message);
        }
        return of(void 0);
      })
    );
  }

  /**
   * Turns a failed call into the one sentence that explains it. The backend's own
   * message wins when it has one, so a 500 reason is not replaced by a generic
   * sentence.
   */
  private describeFailure(err: HttpErrorResponse): string {
    if (err.status === 404) {
      return 'Outbox is disabled.';
    }
    if (err.status === 403) {
      return 'You do not hold MANAGER role.';
    }
    if (err.status === 0) {
      return 'Cannot reach the gateway.';
    }
    const backendMessage =
      err.error && typeof err.error === 'object' && typeof err.error.error === 'string'
        ? err.error.error.trim()
        : '';
    return backendMessage || 'The outbox could not be read.';
  }
}
