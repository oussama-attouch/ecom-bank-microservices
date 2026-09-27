import { LiveEvent } from '../../models/live-event';

/**
 * The colour a row is drawn in, as a name rather than a value.
 *
 * Components map a tone to design tokens in their own stylesheet
 * (`--row-accent: var(--success)` and friends); nothing here knows about CSS.
 * That split is what keeps this function a pure, obvious mapping and keeps the
 * palette in the one file that owns colour (`styles/_tokens.scss`).
 */
export type EventTone = 'primary' | 'accent' | 'success' | 'warning' | 'danger' | 'info' | 'neutral';

/** Every tone, in the order the throughput panel's legend lists them. */
export const EVENT_TONES: readonly EventTone[] = [
  'primary',
  'accent',
  'success',
  'warning',
  'danger',
  'info',
  'neutral'
];

/**
 * Maps an event to its tone.
 *
 * The interesting case is the saga, which has one tone per outcome rather than
 * one per source: a completed transfer, a compensating one and a failed one are
 * the three things an operator is watching this page to see, and drawing them
 * alike would waste the whole point of the feed. The outcome lives in
 * `metadata.status` on a `SAGA_FINISHED` event, which is where
 * `TransferSagaService.recordFinish` puts it.
 *
 * Everything unmatched — including a source this build has never heard of —
 * falls through to `neutral`, so an unknown event is still visible.
 */
export function eventTone(event: Pick<LiveEvent, 'source' | 'type' | 'metadata'>): EventTone {
  switch (event.source) {
    case 'saga':
      if (event.type !== 'SAGA_FINISHED') {
        return 'primary';
      }
      switch (sagaStatus(event)) {
        case 'COMPLETED':
          return 'success';
        case 'COMPENSATING':
          return 'warning';
        case 'FAILED':
          return 'danger';
        default:
          // Finished, but the status is missing or one this build does not know.
          // Primary rather than neutral: it is a saga outcome, and a feed that
          // greyed it out would be quietly discarding the event that matters.
          return 'primary';
      }
    case 'event-store':
      return 'neutral';
    case 'kafka-publisher':
      return 'accent';
    case 'kafka-consumer':
      return 'success';
    case 'billing-consumer':
      return 'info';
    default:
      return 'neutral';
  }
}

/** Tone for a bare source name, for the throughput panel's legend. */
export function sourceTone(source: string): EventTone {
  return eventTone({ source, type: '', metadata: {} });
}

export function sagaStatus(event: Pick<LiveEvent, 'metadata'>): string {
  const status = event.metadata?.['status'];
  return typeof status === 'string' ? status : '';
}

/**
 * A short label for the source badge. Known sources get their proper spelling;
 * anything else is shown as-is rather than hidden, so a new backend source is
 * legible the moment it appears.
 */
export function sourceLabel(source: string): string {
  switch (source) {
    case 'saga':
      return 'saga';
    case 'event-store':
      return 'event store';
    case 'kafka-publisher':
      return 'kafka out';
    case 'kafka-consumer':
      return 'kafka in';
    case 'billing-consumer':
      return 'billing in';
    default:
      return source;
  }
}

/** `HH:mm:ss.SSS` in the viewer's own timezone — a feed is read in local time. */
export function eventClock(timestamp: string): string {
  const at = new Date(timestamp);
  if (Number.isNaN(at.getTime())) {
    return '--:--:--';
  }
  const pad = (value: number, width = 2) => String(value).padStart(width, '0');
  return `${pad(at.getHours())}:${pad(at.getMinutes())}:${pad(at.getSeconds())}.${pad(at.getMilliseconds(), 3)}`;
}

/**
 * The one-line detail shown under a description: whichever metadata fields this
 * event actually carries, in a stable order. Values are stringified only when
 * they are primitives — an object would render as `[object Object]`, which is
 * worse than saying nothing.
 */
export function eventDetail(event: LiveEvent): string {
  const parts: string[] = [];
  const push = (label: string, value: unknown) => {
    if (typeof value === 'string' || typeof value === 'number') {
      parts.push(`${label} ${value}`);
    }
  };
  push('offset', event.metadata?.['offset']);
  push('amount', event.metadata?.['amount']);
  push('steps', event.metadata?.['steps']);
  push('duration', event.metadata?.['durationMs']);
  push('partition', event.metadata?.['partition']);
  return parts.join(' · ');
}
