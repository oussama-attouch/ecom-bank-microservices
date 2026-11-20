import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize } from 'rxjs';
import {
  ALL_SERVICES,
  LogLine,
  LogQuery,
  LogService,
  SERVICES,
  buildLogQuery
} from '../../../services/log.service';

/**
 * The log console: Loki's lines for the eight services, read through the
 * gateway's MANAGER-only route.
 *
 * <h2>Why 1000 fetched / 500 shown</h2>
 * Loki has no cursor and a log view has no page 2. The page fetches a bounded
 * window (1000 entries, newest first) and renders at most 500 of them. The cap
 * is what keeps a browser tab from asking Loki for an unbounded range; the
 * window and the text filter are the pagination. The footer says when a cap was
 * hit, because a silently truncated list reads as "that is all there is".
 *
 * <h2>Why the level filter is local</h2>
 * A server-side level filter is a line filter: `|~ " ERROR "` returns the error
 * line and not the stack-trace lines around it. Those are the lines a reader
 * needs, so the page fetches the window and filters levels here. The tradeoff
 * is stated in the spec and in the page: an error older than the newest 1000
 * entries needs a narrower window or the text filter.
 *
 * <h2>Why the request is cancelled on a new one</h2>
 * A filter change while a 24-hour query is in flight should replace it, not
 * queue behind it. `reload()` unsubscribes the previous subscription; HttpClient
 * aborts the request, and `finalize` keeps the spinner honest. Auto-refresh
 * never starts a second request while one is loading, so the poll cannot stack.
 */
@Component({
  selector: 'app-logs',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './logs.component.html',
  styleUrl: './logs.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LogsComponent implements OnInit, OnDestroy {

  private readonly api = inject(LogService);

  readonly services = SERVICES;
  readonly allServices = ALL_SERVICES;
  readonly levelOptions = ['ALL', 'ERROR', 'WARN', 'INFO', 'DEBUG', 'TRACE'];
  readonly windowOptions = [
    { value: 15, label: '15 min' },
    { value: 60, label: '1 hour' },
    { value: 360, label: '6 hours' },
    { value: 1440, label: '24 hours' }
  ];
  readonly fetchLimit = 1000;
  readonly displayLimit = 500;

  readonly lines = signal<LogLine[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly lastUpdated = signal<Date | null>(null);

  readonly errorCount = computed(() => this.lines().filter((line) => line.level === 'ERROR').length);
  readonly warnCount = computed(() => this.lines().filter((line) => line.level === 'WARN').length);
  /** Lines that carry a trace context and therefore have a Zipkin target. */
  readonly tracedCount = computed(() => this.lines().filter((line) => line.traceId !== null).length);
  /** The traceId copied most recently, for the row's check-mark feedback. */
  readonly copiedTrace = signal<string | null>(null);

  // Form state. Plain fields, not signals: template-driven forms write them and
  // the getters below re-read them on each change-detection pass. Only the
  // fetched data needs to be a signal, because it changes outside a DOM event.
  service = ALL_SERVICES;
  level = 'ALL';
  minutes = 60;
  text = '';
  autoRefresh = true;
  /**
   * Hide the background lines. Eureka heartbeats, Hikari housekeepers and Kafka
   * coordinator threads are not inside a request or a listener observation, so
   * they legitimately have no traceId. That is expected, not missing tracing.
   */
  tracedOnly = false;

  private sub?: Subscription;
  private pollTimer?: ReturnType<typeof setInterval>;
  private searchTimer?: ReturnType<typeof setTimeout>;
  private copyTimer?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    // roleGuard has already waited for the token's roles, so the first request
    // has a bearer token; no `waitForRoles()` is needed here.
    this.reload();
    this.pollTimer = setInterval(() => {
      // document.hidden: a background tab does not need to poll, and the
      // browser throttles it anyway.
      if (document.hidden || !this.autoRefresh || this.loading()) return;
      this.reload();
    }, 5000);
  }

  ngOnDestroy(): void {
    if (this.pollTimer) clearInterval(this.pollTimer);
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.copyTimer) clearTimeout(this.copyTimer);
    this.sub?.unsubscribe();
  }

  /**
   * Lines matching the local filters, before the display cap.
   *
   * RAW lines are kept whatever the level is: they are the stack-trace
   * continuations and banners that carry no level of their own, and dropping
   * them is exactly how an ERROR view loses the stack trace that explains the
   * error. They are also why the level filter is not a LogQL filter.
   *
   * `tracedOnly` narrows to lines that have a traceId. RAW lines are excluded
   * in that mode by definition — they have no trace to open — which is why the
   * trim below only keeps them when the traced filter is off.
   */
  get levelMatched(): LogLine[] {
    const level = this.level;
    let lines = this.lines();

    if (this.tracedOnly) {
      lines = lines.filter((line) => line.traceId !== null);
    }

    return level === 'ALL'
      ? lines
      : lines.filter((line) => line.level === level || (line.raw && !this.tracedOnly));
  }

  /** What the list renders: newest first, capped at 500. */
  get visibleLines(): LogLine[] {
    return this.levelMatched.slice(0, this.displayLimit);
  }

  /** True when either the fetch or the display cap dropped lines. */
  get capped(): boolean {
    return this.lines().length >= this.fetchLimit || this.levelMatched.length > this.displayLimit;
  }

  get queryPreview(): string {
    return buildLogQuery(this.service, this.text);
  }

  get windowLabel(): string {
    return this.minutes >= 60 ? `${this.minutes / 60} h` : `${this.minutes} min`;
  }

  reload(): void {
    if (this.searchTimer) {
      clearTimeout(this.searchTimer);
      this.searchTimer = undefined;
    }

    this.sub?.unsubscribe();
    this.loading.set(true);
    this.error.set(null);

    const query: LogQuery = {
      service: this.service,
      text: this.text,
      minutes: this.minutes,
      limit: this.fetchLimit
    };

    this.sub = this.api.query(query)
      .pipe(finalize(() => this.loading.set(false)))
      .subscribe({
        next: (lines) => {
          this.lines.set(lines);
          this.lastUpdated.set(new Date());
        },
        error: (err) => {
          // Keep the last successful rows: they are the context needed to read
          // the outage, and blanking them would destroy it.
          this.error.set(describeError(err));
        }
      });
  }

  /** Debounced text filter; Enter applies immediately. */
  onSearchInput(): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.searchTimer = undefined;
      this.reload();
    }, 350);
  }

  onSearchEnter(): void {
    this.reload();
  }

  clearFilters(): void {
    this.service = ALL_SERVICES;
    this.level = 'ALL';
    this.minutes = 60;
    this.text = '';
    this.tracedOnly = false;
    this.reload();
  }

  /** Copy the full id; the chip itself only has room for a prefix. */
  copyTrace(traceId: string | null): void {
    if (!traceId || !navigator.clipboard?.writeText) return;

    navigator.clipboard.writeText(traceId)
      .then(() => {
        this.copiedTrace.set(traceId);
        if (this.copyTimer) clearTimeout(this.copyTimer);
        this.copyTimer = setTimeout(() => this.copiedTrace.set(null), 1500);
      })
      .catch(() => {
        // Clipboard permission denied: the Zipkin link still works.
      });
  }

  traceUrl(traceId: string | null): string {
    return `http://localhost:9411/zipkin/traces/${traceId ?? ''}`;
  }

  shortTrace(traceId: string | null): string {
    const id = traceId ?? '';
    return id.length > 12 ? `${id.slice(0, 12)}…` : id;
  }

  grafanaLink = 'http://localhost:3000/explore';
}

/**
 * The page's own error copy. The global interceptor cannot know that a 404 here
 * means "the gateway predates the /loki route" or that a 502 is Loki rather than
 * the application, and a generic toast would not either.
 */
function describeError(err: unknown): string {
  const failure = err as { status?: number; error?: { error?: string }; message?: string };

  switch (failure?.status) {
    case 0:
      return 'The gateway did not answer on :8888. Start it, then Retry.';
    case 401:
      return 'The request carried no access token. Sign in again.';
    case 403:
      return 'This console is MANAGER-only, and the gateway refused the query.';
    case 404:
      return 'The gateway has no /loki route. Restart gateway-service after this change, then Retry.';
    case 502:
    case 504:
      return 'Loki did not answer through the gateway. Check that the loki container is running.';
    default: {
      const loki = failure?.error?.error;
      if (loki) return `Loki: ${loki}`;
      return failure?.message || 'The log query failed. Retry, or check the gateway and Loki.';
    }
  }
}
