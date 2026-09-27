import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { BehaviorSubject, Observable, combineLatest, map } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { LiveEvent, LiveStreamStats, LiveStreamStatus } from '../../../models/live-event';
import { LiveEventService } from '../../../services/live-event.service';
import { EventFeedComponent } from '../event-feed/event-feed.component';
import { ThroughputPanelComponent } from '../throughput-panel/throughput-panel.component';

/** How the status pill reads. */
interface StatusView {
  label: string;
  severity: 'success' | 'warn' | 'danger';
  icon: string;
  hint: string;
}

/** Everything the page renders, resolved in one emission. */
interface StreamView {
  events: LiveEvent[];
  stats: LiveStreamStats;
  paused: boolean;
  /** Events received since the pause began. */
  missed: number;
}

/**
 * The live event stream page.
 *
 * <h2>Pause is a view concern, not a connection concern</h2>
 * Pausing keeps the socket open and keeps the service counting; it freezes only
 * what the feed renders. That is the difference between "I want to read this
 * row" and "stop watching the ledger", and the missed counter is what keeps the
 * first from being a silent lie — a frozen feed that does not say how much has
 * gone past looks exactly like a stream that has gone quiet.
 *
 * The freeze is implemented here rather than in the service on purpose: the
 * service stays a faithful record of the stream, and the page decides how much
 * of it to show.
 */
@Component({
  selector: 'app-live-event-stream',
  standalone: true,
  imports: [CommonModule, ButtonModule, TagModule, EventFeedComponent, ThroughputPanelComponent],
  templateUrl: './live-event-stream.component.html',
  styleUrl: './live-event-stream.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class LiveEventStreamComponent implements OnInit, OnDestroy {

  private readonly stream = inject(LiveEventService);

  private readonly paused$ = new BehaviorSubject(false);

  /** The last window seen while live; the feed shows this once paused. */
  private lastLive: LiveEvent[] = [];
  /** The received-total when the pause began, so "missed" is a difference. */
  private totalAtPause = 0;

  readonly status$: Observable<StatusView> = this.stream.status$.pipe(map(toStatusView));

  readonly view$: Observable<StreamView> = combineLatest([
    this.stream.events$,
    this.stream.stats$,
    this.paused$
  ]).pipe(
    map(([events, stats, paused]) => {
      if (!paused) {
        // Remembering the live window inside map is a side effect in what is
        // otherwise a pure projection. It is deliberate: the alternative is a
        // second subscription holding the same state, which is more moving parts
        // for the same result, and the emission order here is deterministic.
        this.lastLive = events;
        this.totalAtPause = stats.total;
        return { events, stats, paused, missed: 0 };
      }
      return { events: this.lastLive, stats, paused, missed: stats.total - this.totalAtPause };
    })
  );

  paused = false;

  ngOnInit(): void {
    this.stream.connect();
  }

  ngOnDestroy(): void {
    // The socket belongs to this page's lifetime: leaving it open would keep
    // streaming events into a component nobody is looking at.
    this.stream.disconnect();
  }

  togglePause(): void {
    this.paused = !this.paused;
    this.paused$.next(this.paused);
  }

  clear(): void {
    this.stream.clear();
  }
}

function toStatusView(status: LiveStreamStatus): StatusView {
  switch (status) {
    case 'connected':
      return {
        label: 'Connected',
        severity: 'success',
        icon: 'pi pi-circle-fill',
        hint: 'Receiving live events from the ledger.'
      };
    case 'reconnecting':
      return {
        label: 'Reconnecting',
        severity: 'warn',
        icon: 'pi pi-spin pi-spinner',
        hint: 'The stream dropped; retrying with an exponential backoff up to 30s.'
      };
    default:
      return {
        label: 'Disconnected',
        severity: 'danger',
        icon: 'pi pi-circle',
        hint: 'Not connected to the ledger stream.'
      };
  }
}
