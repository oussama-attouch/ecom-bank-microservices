import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import { silentWhen } from './http-context.tokens';

/** The eight application services, in the order the nav and the select show them. */
export const SERVICES = [
  'gateway-service',
  'customer-service',
  'inventory-service',
  'billing-service',
  'order-service',
  'ledger-service',
  'config-service',
  'discovery-service'
] as const;

/** Sentinel for "every application service". */
export const ALL_SERVICES = 'all';

export type LogLevel = 'INFO' | 'WARN' | 'ERROR' | 'DEBUG' | 'TRACE' | 'RAW';

/** One rendered log line, already split into the fields the page shows. */
export interface LogLine {
  /** Stable key for `@for`: Loki's own timestamp plus the stream and index. */
  id: string;
  /** Sort key, from Loki's nanosecond timestamp (not the line's wall clock). */
  timestampMs: number;
  /** `HH:mm:ss.SSS`, taken from the line when it parses. */
  time: string;
  /** Full line timestamp (or a local stamp for a RAW line), for the tooltip. */
  fullStamp: string;
  level: LogLevel;
  service: string;
  thread: string;
  logger: string;
  message: string;
  traceId: string | null;
  spanId: string | null;
  /** True for a line the pattern did not match — a stack-trace continuation or a banner. */
  raw: boolean;
}

export interface LogQuery {
  service: string;
  /** Free text, sent to Loki as a `|=` line filter. Empty means no filter. */
  text: string;
  /** Window in minutes, ending now. */
  minutes: number;
  /** How many entries to fetch; the page displays a subset. */
  limit: number;
}

export interface LokiStream {
  stream?: Record<string, string>;
  values?: string[][];
}

export interface LokiResponse {
  status?: string;
  data?: {
    resultType?: string;
    result?: LokiStream[];
  };
}

/**
 * Reads Loki through the gateway's read-only route.
 *
 * <p>The URL is `/loki/...`, not `http://localhost:3100/...`: the gateway
 * strips `/loki` and forwards to `LOKI_URL`, so the browser stays same-origin
 * and the MANAGER check lives at the edge. Calling Loki's port from the page
 * would work on this machine and nowhere else.
 *
 * <p>Requests are silent for the same reason System Health's polls are: this
 * page renders its own failure banner, and the global toast would fire once per
 * five-second poll during an outage.
 */
@Injectable({ providedIn: 'root' })
export class LogService {

  constructor(private readonly http: HttpClient) {}

  query(query: LogQuery): Observable<LogLine[]> {
    const end = BigInt(Date.now()) * 1_000_000n;
    const minutes = BigInt(Math.max(1, Math.trunc(query.minutes)));
    const start = end - minutes * 60_000_000_000n;

    const params = new HttpParams()
      .set('query', buildLogQuery(query.service, query.text))
      .set('start', start.toString())
      .set('end', end.toString())
      .set('limit', String(query.limit))
      .set('direction', 'backward');

    return this.http
      .get<LokiResponse>('/loki/api/v1/query_range', { params, ...silentWhen(true) })
      .pipe(map((response) => parseLokiResponse(response)));
  }
}

/**
 * The LogQL the page sends. The level filter is deliberately absent: a
 * server-side `|~ " ERROR "` would return the error line and drop the
 * stack-trace lines around it, which are the lines that explain it. The page
 * filters levels over the fetched window instead.
 *
 * <p>`{service=~".+-service"}` for "all" matches the eight names and any future
 * `<name>-service`, and does not drag in the infrastructure containers Promtail
 * also ships under their compose service names.
 */
export function buildLogQuery(service: string, text: string): string {
  const selector = service === ALL_SERVICES
    ? '{service=~".+-service"}'
    : `{service=${quoteLogQl(service)}}`;

  const filter = text.trim();
  return filter ? `${selector} |= ${quoteLogQl(filter)}` : selector;
}

/** Flattens Loki's stream-per-label-set response into one newest-first list. */
export function parseLokiResponse(response: LokiResponse | null): LogLine[] {
  const lines: LogLine[] = [];
  let index = 0;

  for (const stream of response?.data?.result ?? []) {
    const service = stream.stream?.['service'] ?? '';
    for (const value of stream.values ?? []) {
      if (!value || value.length < 2) continue;
      lines.push(parseLine(value[0], value[1], service, index++));
    }
  }

  return lines.sort((a, b) => b.timestampMs - a.timestampMs);
}

/**
 * The logging pattern `specs/006` wrote, and the only parser in the app:
 *
 *   2026-11-19 22:32:55.997 [thread] INFO  [traceId,spanId] logger - message
 *
 * Anything that does not match is kept as a RAW line rather than dropped. That
 * is the same decision Promtail made when it refused a regex stage: a stack
 * trace is the line a reader needs and the line that never matches.
 */
const LOG_PATTERN =
  /^(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[([^\]]+)\] (\w+)\s+\[([0-9a-f]*),([0-9a-f]*)\] (\S+) - ([\s\S]*)$/;

function parseLine(ns: string, raw: string, fallbackService: string, index: number): LogLine {
  const clean = raw.replace(/\r$/, '');
  const timestampMs = nsToMs(ns);
  const match = LOG_PATTERN.exec(clean);

  if (!match) {
    return {
      id: `${ns}:${fallbackService}:${index}`,
      timestampMs,
      time: clockOf(timestampMs),
      fullStamp: new Date(timestampMs).toLocaleString(),
      level: 'RAW',
      service: fallbackService,
      thread: '',
      logger: '',
      message: clean,
      traceId: null,
      spanId: null,
      raw: true
    };
  }

  return {
    id: `${ns}:${fallbackService}:${index}`,
    timestampMs,
    time: match[1].slice(11),
    fullStamp: match[1],
    level: normalizeLevel(match[3]),
    service: fallbackService,
    thread: match[2],
    logger: match[6],
    message: match[7].replace(/\r$/, ''),
    traceId: match[4] || null,
    spanId: match[5] || null,
    raw: false
  };
}

function normalizeLevel(value: string): LogLevel {
  const level = value.toUpperCase();
  switch (level) {
    case 'INFO':
    case 'WARN':
    case 'ERROR':
    case 'DEBUG':
    case 'TRACE':
      return level;
    default:
      return 'RAW';
  }
}

/** Loki returns nanosecond epoch integers as strings; JS numbers lose digits. */
function nsToMs(ns: string): number {
  try {
    return Number(BigInt(ns) / 1_000_000n);
  } catch {
    return Date.now();
  }
}

function clockOf(ms: number): string {
  const date = new Date(ms);
  const pad = (value: number, size = 2) => String(value).padStart(size, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}

/**
 * LogQL string literal. JSON.stringify would also escape control characters as
 * `\uXXXX`, which LogQL's double-quoted parser does not accept; this escapes the
 * four characters a filter realistically contains.
 */
function quoteLogQl(value: string): string {
  const escaped = value
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\r/g, '')
    .replace(/\n/g, '\\n')
    .replace(/\t/g, '\\t');
  return `"${escaped}"`;
}
