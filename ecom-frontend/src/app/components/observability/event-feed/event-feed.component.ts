import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ScrollPanelModule } from 'primeng/scrollpanel';
import { LiveEvent } from '../../../models/live-event';
import {
  EventTone,
  eventClock,
  eventDetail,
  eventTone,
  sagaStatus,
  sourceLabel
} from '../live-event-tone';

/**
 * One rendered row. Built once per emission rather than derived in the template:
 * the feed redraws up to four times a second and a function call per cell per
 * change-detection pass is work the emitter can do exactly once.
 */
export interface FeedRow {
  /** Stable identity for `track`, so existing rows keep their DOM nodes. */
  key: string;
  clock: string;
  tone: EventTone;
  source: string;
  type: string;
  description: string;
  detail: string;
  /** The journey's correlation id, shortened for display. */
  transaction: string | null;
  /** Set only on a finished saga: COMPLETED / COMPENSATING / FAILED. */
  outcome: string;
}

/** The scrolling event list. Presentational: it renders what it is handed. */
@Component({
  selector: 'app-event-feed',
  standalone: true,
  imports: [CommonModule, ScrollPanelModule],
  templateUrl: './event-feed.component.html',
  styleUrl: './event-feed.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EventFeedComponent {

  rows: FeedRow[] = [];

  /** Newest first; already trimmed to the render window by the service. */
  @Input() set events(value: LiveEvent[]) {
    this.rows = (value ?? []).map(toRow);
  }

  /** True while the page is frozen: the rows below are a snapshot. */
  @Input() paused = false;

  /** Events that arrived since the pause began — what the snapshot is missing. */
  @Input() missed = 0;

  /** Ring-buffer occupancy, so the header can say how much history is kept. */
  @Input() buffered = 0;
  @Input() bufferSize = 0;
}

function toRow(event: LiveEvent): FeedRow {
  return {
    key: `${event.timestamp}|${event.source}|${event.description}`,
    clock: eventClock(event.timestamp),
    tone: eventTone(event),
    source: sourceLabel(event.source),
    type: event.type,
    description: event.description,
    detail: eventDetail(event),
    transaction: event.transactionId ? event.transactionId.slice(0, 8) : null,
    outcome: sagaStatus(event)
  };
}
