import { Injectable, inject } from '@angular/core';
import { OidcSecurityService } from 'angular-auth-oidc-client';
import { Client, IMessage } from '@stomp/stompjs';
import { BehaviorSubject, Observable, firstValueFrom } from 'rxjs';
import { LiveEvent, LiveStreamStats, LiveStreamStatus, emptyStats } from '../models/live-event';

/** Events held in memory. Older ones are dropped as new ones arrive. */
export const RING_SIZE = 200;

/** Rows the feed renders. The buffer is deeper than the view on purpose. */
export const RENDER_LIMIT = 30;

/**
 * How often the view is allowed to redraw while events are arriving.
 *
 * Events land in the buffer the moment they arrive, but the *emission* is
 * coalesced onto this tick. One ledger transfer produces ten events in under a
 * second and a portfolio seed produces thousands, so redrawing per event would
 * tie the page's cost to the ledger's busiest moment — and the operator cannot
 * read 200 rows a second anyway. Four redraws a second is faster than the eye
 * needs and independent of how fast the stream is going; whatever arrived inside
 * a tick is shown as one batch, which is what "collapsing the overflow" means
 * here. Nothing is lost by it except intermediate frames: the buffer keeps every
 * event, so the deepest rows of a burst are still there to scroll to.
 */
export const RENDER_TICK_MS = 250;

/** First reconnect delay; doubles per attempt up to {@link MAX_BACKOFF_MS}. */
export const BASE_BACKOFF_MS = 1_000;

/** Ceiling for the backoff. Also the retry period once the ceiling is reached. */
export const MAX_BACKOFF_MS = 30_000;

/** Trailing window for the events/sec readout. */
const RATE_WINDOW_MS = 1_000;

/** Upper bound on the arrival log, so a burst cannot grow it without limit. */
const MAX_ARRIVALS = 5_000;

/** The topic the ledger broadcasts every instrumented event on. */
export const EVENTS_TOPIC = '/topic/events';

/**
 * The browser's connection to the ledger's live event stream.
 *
 * <h2>Why the token goes in the URL</h2>
 * The browser WebSocket API takes a URL and a subprotocol list and nothing else,
 * so this connection cannot be authenticated the way every other call in the app
 * is, with an `Authorization` header. The gateway accepts `?access_token=` — and
 * only on a WebSocket handshake for this path, and it strips it again before
 * forwarding — so the token is read and attached here. See
 * `gateway-service/SecurityConfig`.
 *
 * <h2>Why the token is re-read on every attempt</h2>
 * Reconnects can be minutes apart after the backoff reaches its ceiling, and an
 * access token that was valid when the page loaded may not be when the ledger
 * comes back. Reading it per attempt means a reconnect either succeeds or fails
 * for a reason that is still true, instead of replaying a stale credential
 * forever.
 */
@Injectable({ providedIn: 'root' })
export class LiveEventService {

  /**
   * Injected directly rather than through `AuthService`, which exposes the
   * user's name and roles but no access token. `auth.interceptor.ts` already
   * reads the token this way, so this follows the pattern that exists instead of
   * widening another service to carry a credential for one caller.
   */
  private readonly oidc = inject(OidcSecurityService);

  private readonly eventsSubject = new BehaviorSubject<LiveEvent[]>([]);
  private readonly statsSubject = new BehaviorSubject<LiveStreamStats>(emptyStats());
  private readonly statusSubject = new BehaviorSubject<LiveStreamStatus>('disconnected');

  /** Newest first, capped at {@link RING_SIZE}. */
  readonly events$: Observable<LiveEvent[]> = this.eventsSubject.asObservable();
  readonly stats$: Observable<LiveStreamStats> = this.statsSubject.asObservable();
  readonly status$: Observable<LiveStreamStatus> = this.statusSubject.asObservable();

  private buffer: LiveEvent[] = [];
  /** Arrival instants (browser clock) inside the rate window, oldest first. */
  private arrivals: number[] = [];
  private perSource: Record<string, number> = {};
  private total = 0;
  private lastEventAt: string | null = null;

  private client: Client | null = null;
  private attempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private tickTimer: ReturnType<typeof setTimeout> | null = null;
  /** True once the caller has asked for the stream to be down. */
  private stopped = true;
  /** Last transport-level failure, for the reconnect tooltip. */
  private lastError: string | null = null;

  /** Opens the stream. Idempotent: a second call while running does nothing. */
  connect(): void {
    if (!this.stopped) {
      return;
    }
    this.stopped = false;
    this.attempt = 0;
    this.lastError = null;
    void this.openSocket();
  }

  /** Closes the stream and stops retrying. Safe to call when already stopped. */
  disconnect(): void {
    this.stopped = true;
    this.clearTimers();
    const client = this.client;
    this.client = null;
    if (client) {
      void client.deactivate();
    }
    this.statusSubject.next('disconnected');
  }

  /**
   * Forgets every event and counter. The connection is deliberately untouched:
   * "start counting again from here" is a different intention from "stop
   * watching", and conflating them would make the Clear button drop the socket.
   */
  clear(): void {
    this.buffer = [];
    this.arrivals = [];
    this.perSource = {};
    this.total = 0;
    this.lastEventAt = null;
    this.emit();
  }

  /** The most recent transport error, if any — shown as the pill's tooltip. */
  getLastError(): string | null {
    return this.lastError;
  }

  private async openSocket(): Promise<void> {
    if (this.stopped) {
      return;
    }
    // Amber while an attempt is in flight: the page cannot know yet whether this
    // one will land, and showing green optimistically would be a lie the
    // operator only discovers when the feed stays empty.
    this.statusSubject.next('reconnecting');

    const token = await this.readToken();
    if (this.stopped) {
      return;
    }
    if (!token) {
      this.scheduleReconnect('no access token available');
      return;
    }

    // A fresh Client per attempt rather than reusing one: reconnecting a client
    // that has already been closed and deactivated is the kind of state machine
    // that fails in ways only visible under a real outage, and this object is
    // cheap to rebuild.
    const client = new Client({
      brokerURL: this.streamUrl(token),
      // This service does its own backoff (below), so the library's is off —
      // two retry loops would multiply into an unpredictable schedule.
      reconnectDelay: 0,
      // The ledger's broker is a SimpleBroker with no task scheduler, so it
      // negotiates heartbeats to zero whatever is asked for here. Setting them
      // explicitly records that no liveness signal is expected on this socket:
      // a dead ledger is noticed by the TCP close, or by the gateway dropping
      // the proxied socket.
      heartbeatIncoming: 0,
      heartbeatOutgoing: 0,
      debug: () => {
        // Off by default; set to console.debug when diagnosing a handshake.
      },
      onConnect: () => {
        if (this.stopped || this.client !== client) {
          return;
        }
        this.attempt = 0;
        this.lastError = null;
        this.statusSubject.next('connected');
        client.subscribe(EVENTS_TOPIC, (message) => this.onMessage(message));
      },
      onStompError: (frame) => {
        // The broker rejected something at the protocol level. It usually closes
        // the socket immediately after, but not always, so this schedules its own
        // retry; scheduleReconnect ignores the second call.
        this.lastError = frame.headers['message'] ?? 'STOMP error';
        this.scheduleReconnect(this.lastError);
      },
      onWebSocketClose: (event) => {
        if (this.stopped || this.client !== client) {
          return;
        }
        if (event?.reason) {
          this.lastError = event.reason;
        }
        this.scheduleReconnect('socket closed');
      },
      onWebSocketError: () => {
        // Always followed by a close, which does the scheduling. Recorded so the
        // pill can explain why it is amber.
        this.lastError = 'websocket error';
      }
    });

    this.client = client;
    client.activate();
  }

  /**
   * Reads a current access token.
   *
   * `getAccessToken()` rejects or emits an empty string when there is no session
   * yet; both mean the same thing here, and neither should escape as an
   * unhandled rejection out of a socket callback.
   */
  private async readToken(): Promise<string> {
    try {
      return (await firstValueFrom(this.oidc.getAccessToken())) ?? '';
    } catch {
      return '';
    }
  }

  /**
   * Stream URL, derived from the page's own origin so the same build works on
   * localhost:4200 and anywhere else it is served. `/ledger-service` is the
   * dev-server proxy path (`proxy.conf.json`, with `ws: true`); the proxy
   * forwards the upgrade to the gateway, which validates the token and strips it
   * before the ledger sees the request.
   */
  private streamUrl(token: string): string {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    return `${scheme}://${location.host}/ledger-service/ws/events?access_token=${encodeURIComponent(token)}`;
  }

  /** One frame carries a JSON array of events; see `LiveEvent`. */
  private onMessage(message: IMessage): void {
    let batch: unknown;
    try {
      batch = JSON.parse(message.body);
    } catch {
      return;
    }
    if (!Array.isArray(batch)) {
      return;
    }
    for (const event of batch as LiveEvent[]) {
      this.absorb(event);
    }
    this.scheduleTick();
  }

  private absorb(event: LiveEvent): void {
    this.buffer.unshift(event);
    if (this.buffer.length > RING_SIZE) {
      this.buffer.length = RING_SIZE;
    }
    this.total += 1;
    this.perSource[event.source] = (this.perSource[event.source] ?? 0) + 1;
    this.lastEventAt = event.timestamp;

    const now = Date.now();
    this.arrivals.push(now);
    if (this.arrivals.length > MAX_ARRIVALS) {
      this.arrivals.splice(0, this.arrivals.length - MAX_ARRIVALS);
    }
    this.trimArrivals(now);
  }

  /** Drops arrivals that have fallen out of the trailing rate window. */
  private trimArrivals(now: number): void {
    const cutoff = now - RATE_WINDOW_MS;
    while (this.arrivals.length > 0 && this.arrivals[0] < cutoff) {
      this.arrivals.shift();
    }
  }

  /**
   * Requests a redraw, at most one per {@link RENDER_TICK_MS}. A burst inside
   * one window therefore collapses into a single emission.
   */
  private scheduleTick(): void {
    if (this.tickTimer !== null) {
      return;
    }
    this.tickTimer = setTimeout(() => {
      this.tickTimer = null;
      const now = Date.now();
      this.trimArrivals(now);
      this.emit();
      // Keep ticking while the rate window still holds arrivals, so events/sec
      // decays back to zero after a burst instead of freezing at its peak.
      if (this.arrivals.length > 0) {
        this.scheduleTick();
      }
    }, RENDER_TICK_MS);
  }

  private emit(): void {
    this.eventsSubject.next(this.buffer.slice(0, RENDER_LIMIT));
    this.statsSubject.next({
      total: this.total,
      buffered: this.buffer.length,
      bufferSize: RING_SIZE,
      visible: Math.min(this.buffer.length, RENDER_LIMIT),
      perSource: { ...this.perSource },
      perSecond: this.arrivals.length,
      lastEventAt: this.lastEventAt
    });
  }

  /**
   * Schedules the next attempt on an exponential backoff: 1s, 2s, 4s, 8s … to a
   * 30s ceiling. A pending attempt is never replaced, so a close arriving on top
   * of a STOMP error cannot shorten the wait or start two loops.
   */
  private scheduleReconnect(reason: string): void {
    if (this.stopped || this.reconnectTimer !== null) {
      return;
    }
    this.lastError = this.lastError ?? reason;
    this.statusSubject.next('reconnecting');
    const delay = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2 ** this.attempt);
    this.attempt += 1;
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = null;
      void this.openSocket();
    }, delay);
  }

  private clearTimers(): void {
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    if (this.tickTimer !== null) {
      clearTimeout(this.tickTimer);
      this.tickTimer = null;
    }
  }
}
