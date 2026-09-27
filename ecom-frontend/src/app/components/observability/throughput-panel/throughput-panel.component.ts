import { ChangeDetectionStrategy, Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';
import { LiveStreamStats, emptyStats } from '../../../models/live-event';
import { EventTone, sourceLabel, sourceTone } from '../live-event-tone';

/** One line of the per-source legend. */
export interface SourceRow {
  source: string;
  label: string;
  tone: EventTone;
  count: number;
}

/**
 * The counters beside the feed: how much is arriving, from where, and how much
 * of it the page is still holding.
 *
 * Counters are cumulative for the session and are only reset by Clear, so the
 * panel answers "what has happened since I started watching" rather than "what
 * is on screen" — which is the question the feed's own header answers.
 */
@Component({
  selector: 'app-throughput-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './throughput-panel.component.html',
  styleUrl: './throughput-panel.component.scss',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ThroughputPanelComponent {

  legend: SourceRow[] = [];
  current: LiveStreamStats = emptyStats();

  @Input() set stats(value: LiveStreamStats) {
    this.current = value ?? emptyStats();
    this.legend = toLegend(this.current);
  }
}

/**
 * Builds the legend, keeping the order the sources are declared in rather than
 * the order they first appeared: rows that reorder themselves as a transfer
 * moves through the pipeline are much harder to read at a glance. A source the
 * frontend does not know about is appended rather than dropped.
 */
function toLegend(stats: LiveStreamStats): SourceRow[] {
  const counts = stats?.perSource ?? {};
  const known = ['saga', 'event-store', 'kafka-publisher', 'kafka-consumer', 'billing-consumer'];
  const unknown = Object.keys(counts).filter((source) => !known.includes(source)).sort();
  return [...known, ...unknown]
    .map((source) => ({
      source,
      label: sourceLabel(source),
      tone: sourceTone(source),
      count: counts[source] ?? 0
    }))
    // A source that has not been seen yet would be a row of zeroes; the legend is
    // more useful listing only what this ledger has actually produced.
    .filter((row) => row.count > 0);
}
