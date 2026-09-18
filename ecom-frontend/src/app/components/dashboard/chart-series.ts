import { ChartSeries } from '../../models';

/**
 * Per-range aggregation rules for the Command Center charts, kept as pure
 * functions for the same reason as ./kpi-trends: the interesting parts — how a
 * 90-day window becomes 13 weekly buckets, and how a sparse day series gets
 * zero-filled — are worth testing without standing up the dashboard's HTTP
 * polling, theme observer and Chart.js instances.
 *
 * The server already aggregates by day. What is left to do here is make that
 * readable: 365 daily points in a 500px-wide card is a smear, so longer ranges
 * are rolled up into weeks or months.
 */

/** Which range token a chart was drawn for. Mirrors the server's `range` echo. */
export type ChartRange = '7d' | '30d' | '90d' | '1y' | 'all';

/** How wide one plotted bucket is. */
export type BucketSize = 'day' | 'week' | 'month';

/** One plotted point: a bucket's summed values, labelled by its first day. */
export interface ChartBuckets {
  range: ChartRange;
  bucket: BucketSize;
  /** One label per bucket, oldest first — the first day of the bucket. */
  labels: string[];
  volume: number[];
  flowIn: number[];
  flowOut: number[];
  /** X-axis tick thinning: label every nth point (1 = label all of them). */
  tickStride: number;
}

/**
 * Bucket width per range.
 *
 * 7d and 30d are already few enough points to read day by day. The ranges above
 * them are 91-708 points, which is where the roll-up starts.
 */
const BUCKET_SIZE: Record<ChartRange, BucketSize> = {
  '7d': 'day',
  '30d': 'day',
  '90d': 'week',
  '1y': 'month',
  all: 'month'
};

/**
 * Axis length for the day- and week-bucketed ranges, inclusive of today.
 *
 * 90d is 91 on purpose: thirteen whole weeks, so the weekly roll-up has no
 * partial bucket at either end. ALL is not here — it is anchored to the oldest
 * month the payload holds rather than to the clock.
 */
const SPAN_DAYS: Record<'7d' | '30d' | '90d', number> = {
  '7d': 7,
  '30d': 30,
  '90d': 91
};

/** One day in milliseconds, the unit all the day arithmetic below works in. */
const DAY_MS = 86_400_000;

/**
 * How many months a 1y chart reaches back, counting the current one: twelve
 * monthly buckets, the earliest being the 1st of the month eleven back.
 */
const MONTHS_BACK_1Y = 11;

/** The four ranges the charts draw from the endpoint, in the server's own spelling. */
export const CHART_RANGES: ChartRange[] = ['7d', '30d', '90d', '1y', 'all'];

/** The bucket width this range is plotted at. */
export function bucketSize(range: ChartRange): BucketSize {
  return BUCKET_SIZE[range];
}

/** True for a string the server would accept as a range token. */
export function isChartRange(value: string | null | undefined): value is ChartRange {
  return typeof value === 'string' && (CHART_RANGES as string[]).includes(value);
}

/**
 * The bucket width for a range token of unknown provenance — a payload the
 * server echoed back, for instance. Tokens the endpoint does not know fall back
 * to daily, which is the finest bucket and therefore never overstates what the
 * series holds.
 */
export function bucketSizeOf(range: string | null | undefined): BucketSize {
  return isChartRange(range) ? bucketSize(range) : 'day';
}

/** The axis tick stride this range is labelled with. */
export function tickStride(range: ChartRange): number {
  switch (range) {
    case '30d': return 3;   // every third day: 30 day labels do not fit a half-width card
    case '90d': return 2;   // every second week
    case '1y':
    case 'all': return 3;   // every third month
    default: return 1;      // every day
  }
}

/** Short word for the card headers: how wide each plotted point is. */
export function bucketLabel(bucket: BucketSize): string {
  return bucket === 'day' ? 'daily' : bucket === 'week' ? 'weekly' : 'monthly';
}

/**
 * True when the range is cheap enough to re-fetch on the 5s poll.
 *
 * 7d and 30d are at most 31 grouped rows. 90d/1y/all are 91-708 points and only
 * change when the window moves, so they are fetched on the range change alone.
 */
export function isPollRange(range: ChartRange): boolean {
  return range === '7d' || range === '30d';
}

/** True when the payload has anything to draw. */
export function hasChartData(series: ChartSeries | null): boolean {
  return !!series && (series.dailyVolume?.length ?? 0) + (series.dailyFlow?.length ?? 0) > 0;
}

/** `YYYY-MM-DD` for a local date: the calendar day the operator is looking at. */
function isoDay(date: Date): string {
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * `YYYY-MM-DD` for a UTC-midnight timestamp.
 *
 * Day arithmetic on `YYYY-MM-DD` strings goes through UTC (see `dayNumber`), so
 * the results have to be read back in UTC too. Reading them with the local
 * getters instead puts the axis a day out for every operator east of Greenwich.
 */
function utcDay(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** `YYYY-MM-DD` of an instant, without round-tripping through UTC. */
function dayOf(iso: string): string {
  return (iso || '').slice(0, 10);
}

/** Day counter for a `YYYY-MM-DD` string, so day arithmetic is not timestamp arithmetic. */
function dayNumber(day: string): number {
  const [y, m, d] = day.split('-').map(Number);
  return Math.floor(Date.UTC(y, (m || 1) - 1, d || 1) / 86_400_000);
}

/** Calendar month index (year × 12 + month), for bucketing months. */
function monthIndex(day: string): number {
  const [y, m] = day.split('-').map(Number);
  return y * 12 + ((m || 1) - 1);
}

/** The oldest day either series mentions, or null when both are empty. */
function oldestDay(series: ChartSeries): string | null {
  let oldest: string | null = null;
  for (const point of [...(series.dailyVolume ?? []), ...(series.dailyFlow ?? [])]) {
    const day = dayOf(point.date);
    if (day && (oldest === null || day < oldest)) oldest = day;
  }
  return oldest;
}

/**
 * First day on the axis.
 *
 * Daily and weekly ranges are a plain day count ending today. The month ranges
 * are anchored to the first of a month, which is what makes their bucket count
 * come out even: a 1y chart is the twelve months up to and including this one,
 * not the fourteen a 365-day window spans when read inclusively. ALL reaches
 * back only as far as the ledger holds data — an epoch anchor would spend
 * buckets on years it did not exist for.
 *
 * `today` is a local calendar day, so it is anchored at UTC midnight here: all
 * the arithmetic below is in UTC days, and mixing the two shifts the axis by a
 * day for every operator east of Greenwich.
 */
function axisStart(series: ChartSeries, range: ChartRange, today: string): string {
  const anchor = Date.parse(`${today}T00:00:00Z`);

  if (range === 'all') {
    const oldest = oldestDay(series);
    if (!oldest) return today;
    const [oldestYear, oldestMonth] = ymd(oldest);
    return utcDay(new Date(Date.UTC(oldestYear, oldestMonth - 1, 1)));
  }

  if (range === '1y') {
    const [year, month] = ymd(today);
    return utcDay(new Date(Date.UTC(year, month - 1 - MONTHS_BACK_1Y, 1)));
  }

  return utcDay(new Date(anchor - (SPAN_DAYS[range] - 1) * DAY_MS));
}

/** The year, month (1-based) and day of a `YYYY-MM-DD` string. */
function ymd(day: string): [number, number, number] {
  const [year, month, date] = day.split('-').map(Number);
  return [year, month, date];
}

/** Every day from `start` to `today`, inclusive, oldest first. */
function dayAxis(start: string, today: string): string[] {
  const days: string[] = [];
  const end = dayNumber(today);
  for (let n = dayNumber(start); n <= end; n++) {
    days.push(new Date(n * 86_400_000).toISOString().slice(0, 10));
  }
  return days;
}

/**
 * Which bucket a day belongs to, indexed from the axis start.
 *
 * Boundaries are counted from the axis rather than forked for the 90d case, so
 * weekly roll-up still yields 13 buckets over a 91-day window: day 90 lands in
 * week 12. Months use the calendar so a February bucket is not three times
 * wider than the January one next to it.
 */
function bucketIndex(axisStartDay: string, day: string, bucket: BucketSize): number {
  if (bucket === 'day') return dayNumber(day) - dayNumber(axisStartDay);
  if (bucket === 'week') return Math.floor((dayNumber(day) - dayNumber(axisStartDay)) / 7);
  return monthIndex(day) - monthIndex(axisStartDay);
}

/** `2025-06-08` → `Jun 8`; month buckets → `Jun 2025`. Locale-aware, like the rest of the dashboard. */
export function bucketTickLabel(day: string, bucket: BucketSize): string {
  const parsed = new Date(`${day}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime())) return day;
  const options: Intl.DateTimeFormatOptions = bucket === 'month'
    ? { month: 'short', year: 'numeric', timeZone: 'UTC' }
    : { month: 'short', day: 'numeric', timeZone: 'UTC' };
  return parsed.toLocaleDateString(undefined, options);
}

/** Pick a bucket's value at `index`, or 0 for a bucket with no series row behind it. */
function at(values: number[], index: number): number {
  return index >= 0 && index < values.length ? values[index] : 0;
}

/**
 * The range a payload is plotted as. Tokens the endpoint does not know — which
 * only reach here if the server ever grows one — fall back to the default
 * range, so the axis, the bucket width and the stride are all chosen for the
 * same range instead of each guessing separately.
 */
function rangeOf(token: string | null | undefined): ChartRange {
  return isChartRange(token) ? token : '7d';
}

/**
 * Roll the server's daily series up into the buckets this range is plotted at.
 *
 * The volume and flow series are sparse — a day with no postings has no row —
 * so the axis is rebuilt from the range and every bucket is accumulated from a
 * lookup, which zero-fills the quiet days for free.
 */
export function bucketedSeries(series: ChartSeries, today = isoDay(new Date())): ChartBuckets {
  const range = rangeOf(series.range);
  const bucket = bucketSize(range);
  const stride = tickStride(range);
  const start = axisStart(series, range, today);
  const axis = dayAxis(start, today);

  const binCount = axis.reduce((max, day) => Math.max(max, bucketIndex(start, day, bucket) + 1), 0);
  const volume = new Array<number>(binCount).fill(0);
  const flowIn = new Array<number>(binCount).fill(0);
  const flowOut = new Array<number>(binCount).fill(0);
  const labels = new Array<string>(binCount).fill('');

  for (const day of axis) {
    const bin = bucketIndex(start, day, bucket);
    if (bin < 0 || bin >= binCount) continue;
    // The axis is ordered, so the first day that lands in a bucket names it.
    if (!labels[bin]) labels[bin] = bucketTickLabel(day, bucket);
  }

  for (const point of series.dailyVolume ?? []) {
    const bin = bucketIndex(start, dayOf(point.date), bucket);
    if (bin >= 0 && bin < binCount) volume[bin] += Number(point.count) || 0;
  }

  for (const point of series.dailyFlow ?? []) {
    const bin = bucketIndex(start, dayOf(point.date), bucket);
    if (bin < 0 || bin >= binCount) continue;
    flowIn[bin] += Number(point.in) || 0;
    flowOut[bin] += Number(point.out) || 0;
  }

  return { range, bucket, labels, volume, flowIn, flowOut, tickStride: stride };
}

/** Sum of a flow series, for the card's no-activity check and tooltips. */
export function sum(values: number[]): number {
  return values.reduce((total, value) => total + (Number(value) || 0), 0);
}
