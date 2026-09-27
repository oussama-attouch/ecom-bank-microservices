/**
 * One event on the ledger's live stream.
 *
 * The shape mirrors what `ledger-service` puts on the wire (`LiveEvent.java`)
 * exactly, including the fact that the payload is always a JSON **array** — one
 * STOMP frame per 100ms flush carries a batch, even when the batch holds a single
 * event. `LiveEventService` unwraps it, so everything above this file deals in
 * single events.
 *
 * `source` and `type` are plain `string` rather than unions on purpose. They are
 * the server's vocabulary: a new instrumented source appearing in a future
 * backend change should show up on this page as an unfamiliar badge, not as a
 * TypeScript error that stops the build. The known values are exported below so
 * the UI can name them without the type forbidding the unknown.
 */
export interface LiveEvent {
  /** The event's own instant, ISO-8601 — not when the browser received it. */
  timestamp: string;
  /** Where it came from: see {@link LIVE_EVENT_SOURCES}. */
  source: string;
  /** What happened: see {@link LIVE_EVENT_TYPES}. */
  type: string;
  /** Human-readable summary, already assembled by the service that emitted it. */
  description: string;
  /** Correlates the events of one transfer; null where there is nothing to correlate. */
  transactionId: string | null;
  /** Source-specific detail — log offset, aggregate id, saga status, amount. */
  metadata: Record<string, unknown>;
}

/** The sources this build knows how to colour and label. */
export const LIVE_EVENT_SOURCES = [
  'saga',
  'event-store',
  'kafka-publisher',
  'kafka-consumer',
  'billing-consumer'
] as const;

/** The event types this build knows how to label. */
export const LIVE_EVENT_TYPES = [
  'SAGA_STEP',
  'SAGA_FINISHED',
  'EVENT_APPENDED',
  'KAFKA_PUBLISHED',
  'KAFKA_CONSUMED'
] as const;

/** Connection state of the stream, as the status pill renders it. */
export type LiveStreamStatus = 'connected' | 'reconnecting' | 'disconnected';

/**
 * Counters behind the throughput panel.
 *
 * `total` and `perSource` count every event absorbed, including ones that were
 * collapsed out of a render tick and ones already pushed out of the ring buffer
 * — they are the honest totals. `buffered` is what is still in memory and
 * `visible` is what the feed is actually showing, so the three together say how
 * much of the stream the page is presenting.
 */
export interface LiveStreamStats {
  total: number;
  buffered: number;
  bufferSize: number;
  visible: number;
  perSource: Record<string, number>;
  /** Events received in the last second, measured at arrival. */
  perSecond: number;
  lastEventAt: string | null;
}

export function emptyStats(): LiveStreamStats {
  return {
    total: 0,
    buffered: 0,
    bufferSize: 0,
    visible: 0,
    perSource: {},
    perSecond: 0,
    lastEventAt: null
  };
}
