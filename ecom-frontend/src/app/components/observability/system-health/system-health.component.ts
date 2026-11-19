import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import { Observable, catchError, exhaustMap, finalize, forkJoin, interval, of, startWith, tap } from 'rxjs';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  CircuitBreakerView,
  ServiceHealth,
  SystemHealthService
} from '../../../services/system-health.service';

/**
 * The single pane of glass: fleet health, circuit breakers, and the time-series
 * that neither can show.
 *
 * <h2>Why the poll is `exhaustMap`</h2>
 * A five-second interval that fires regardless of the previous request would
 * stack up exactly when the aggregator is slow — which is when it is broken, and
 * which turns one wedged service into an unbounded queue of browser requests.
 * `exhaustMap` drops a tick while one is still in flight, so a refresh cycle can
 * never outrun the gateway.
 *
 * <h2>Why nothing here throws</h2>
 * Both calls catch to `null`. The page has three independent fates — gateway
 * unreachable, breakers unreadable, Grafana blocked — and each is shown on its
 * own row. A page that blanks entirely because one panel failed is the failure
 * mode this layout exists to avoid.
 */
@Component({
  selector: 'app-system-health',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './system-health.component.html',
  styleUrl: './system-health.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class SystemHealthComponent {

  private readonly api = inject(SystemHealthService);
  private readonly destroyRef = inject(DestroyRef);

  readonly services = signal<ServiceHealth[]>([]);
  readonly breakers = signal<CircuitBreakerView[]>([]);
  readonly overall = signal<string>('UNKNOWN');
  readonly timestamp = signal<string | null>(null);

  /** The gateway itself did not answer — a global failure, not a per-service one. */
  readonly gatewayDown = signal(false);
  /** The gateway answered but the breaker fan-out did not come back. */
  readonly breakersDown = signal(false);
  readonly refreshing = signal(false);

  /**
   * The Grafana URL, in the two forms the page needs.
   *
   * <p>`grafanaUrl` is sanitized because an `<iframe src>` is bound in Angular's
   * strictest security context (RESOURCE_URL) and a cross-origin http URL is
   * rejected by the binding itself — that alone renders a blank frame, with the
   * only evidence being an "unsafe value used in a resource URL context" message
   * in the console. `bypassSecurityTrustResourceUrl` fixes exactly that and
   * nothing else.
   *
   * <p>It does <strong>not</strong> defeat `X-Frame-Options` or a
   * `frame-ancestors` CSP: those are sent by Grafana and enforced by the browser
   * on Grafana's response, so no client-side call can override them. If the frame
   * is still blank after this, the cause is on the Grafana side — check
   * `GF_SECURITY_ALLOW_EMBEDDING` and the response headers. Trusting the value is
   * safe here because it is a compile-time constant, never user input.
   */
  private readonly sanitizer = inject(DomSanitizer);
  readonly grafanaLink = 'http://localhost:3000/d/ecom-bank-overview?kiosk&refresh=10s&theme=dark';
  readonly grafanaUrl: SafeResourceUrl =
    this.sanitizer.bypassSecurityTrustResourceUrl(this.grafanaLink);

  constructor() {
    interval(5000)
      .pipe(
        startWith(0),
        exhaustMap(() => this.refresh()),
        takeUntilDestroyed(this.destroyRef)
      )
      .subscribe();
  }

  /** Card border/tone for a status. LAGGING is neither healthy nor broken. */
  tone(status: string | null | undefined): 'up' | 'lagging' | 'down' | 'unknown' {
    switch ((status ?? '').toUpperCase()) {
      case 'UP':
        return 'up';
      case 'LAGGING':
        return 'lagging';
      case 'DOWN':
        return 'down';
      default:
        return 'unknown';
    }
  }

  /** Circuit breaker badge. HALF_OPEN is transitional, so it gets its own tone. */
  breakerTone(state: string | null | undefined): 'up' | 'lagging' | 'down' | 'unknown' {
    switch ((state ?? '').toUpperCase()) {
      case 'CLOSED':
        return 'up';
      case 'HALF_OPEN':
      case 'HALF-OPEN':
        return 'lagging';
      case 'OPEN':
        return 'down';
      default:
        return 'unknown';
    }
  }

  /** `null` means "no calls recorded yet" — a dash, not 0%. */
  failureText(breaker: CircuitBreakerView): string {
    return breaker.failureRate === null ? '—' : `${breaker.failureRate.toFixed(1)}%`;
  }

  refreshNow(): void {
    this.refresh().subscribe();
  }

  private refresh(): Observable<unknown> {
    this.refreshing.set(true);
    return forkJoin({
      health: this.api.systemHealth().pipe(catchError(() => of(null))),
      breakers: this.api.circuitBreakers().pipe(catchError(() => of(null)))
    }).pipe(
      tap(({ health, breakers }) => {
        if (health) {
          this.services.set(health.services ?? []);
          this.overall.set(health.overallStatus ?? 'UNKNOWN');
          this.timestamp.set(health.timestamp);
          this.gatewayDown.set(false);
        } else {
          // Keep the last known cards on screen but say the data is not live:
          // blanking them would destroy the context needed to understand the outage.
          this.gatewayDown.set(true);
        }
        if (breakers) {
          this.breakers.set(breakers.circuitBreakers ?? []);
          this.breakersDown.set(false);
        } else {
          this.breakers.set([]);
          this.breakersDown.set(true);
        }
      }),
      finalize(() => this.refreshing.set(false)),
      catchError(() => of(null))
    );
  }
}
