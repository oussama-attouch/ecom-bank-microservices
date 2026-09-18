import { KpiTrend } from '../../models';

/**
 * The KPI trend rules from brief 5.3, kept as pure functions.
 *
 * These are the parts worth testing directly: which way a value moved and
 * whether that movement is good news — a question whose answer is per-KPI, not
 * per-sign. Keeping them out of the component means the rules can be exercised
 * without standing up the dashboard's HTTP polling, theme observer and charts.
 */

/** Which way a KPI's value moved over the window. */
export type TrendDirection = 'up' | 'down' | 'flat';

/** Semantic colour for a trend: green when the movement is good news, red when it is not. */
export type TrendTone = 'good' | 'bad' | 'neutral';

/**
 * The card-facing inputs these helpers need. `KpiCard` satisfies it, and a test
 * can build one with three fields instead of the whole component.
 */
export interface TrendSource {
  /**
   * What "up" means for this KPI: more assets is a win, more failing sagas is
   * not. Omitted for a KPI whose movement is neither good nor bad news — the
   * size of an average transaction, the count of large transfers (brief 5.3's
   * neutral row) — which reads grey whichever way it went.
   */
  upIsGood?: boolean;
  prefix: string;
  /** Unit trail, for the cards whose value reads as a percentage or a duration. */
  suffix?: string;
  decimals: number;
  /** Tooltip lead-in for KPIs whose displayed value is scoped to the window. */
  trendTitle?: string;
  /**
   * The value this KPI is expected to hold, in the card's own unit: 95 on a
   * percentage card means 95%, 500 on a duration card means 500 ms. A card with
   * a target carries a threshold dot, green when the value is on the right side
   * of it and red when it is not.
   *
   * Only meaningful alongside `upIsGood`: a target says "good enough", which is
   * a judgement, and a KPI with no semantic direction has nothing to judge.
   */
  target?: number;
  /**
   * What the trend line compares against, when it is not the shared window —
   * e.g. the SLA card's "From previous 24h", or "From previous 30d" on every
   * card whose value follows the period selector. Defaults to "From last week".
   */
  trendPeriod?: string;
  /** The same override for the tooltip's prose. Defaults to "a week ago". */
  previousLabel?: string;
  /**
   * Why there is no percentage to show, when the card knows better than the
   * default sentence — the `all` range has no earlier period at all, which is a
   * different statement from "the value was zero".
   */
  noBaselineNote?: string;
}

/** Whether a value is on the right side of its card's target. */
export type ThresholdState = 'met' | 'missed';

/** Sub-0.01% moves are noise: the delta is displayed to two decimals. */
const FLAT_BAND = 0.005;

/**
 * Which way the KPI moved; `flat` inside a 0.01% dead band around zero.
 *
 * <p>`null` means there is no measurement at all — no trend payload, or a null
 * delta because the value was zero a week ago — which is a different statement
 * from "did not change" and is rendered as a dash.
 */
export function trendDirection(trend: KpiTrend | null | undefined): TrendDirection | null {
  const delta = trend?.deltaPercent;
  if (typeof delta !== 'number' || !Number.isFinite(delta)) return null;
  if (Math.abs(delta) < FLAT_BAND) return 'flat';
  return delta > 0 ? 'up' : 'down';
}

/**
 * True when there is a percentage worth showing.
 *
 * The backend also returns 0% for 0-against-0 — nothing there then, nothing
 * there now — but printing "0.00% From last week" for a KPI that has never held
 * a value claims a measurement where there is none, so a zero baseline reads as
 * a dash instead.
 */
export function hasTrendPercentage(trend: KpiTrend | null | undefined): boolean {
  if (trendDirection(trend) === null) return false;
  return Number(trend?.previous) > 0;
}

/**
 * Good/bad/neutral for a card's trend line and, through it, its sparkline.
 *
 * The direction is the direction of the *value* (up when the delta is positive),
 * and only then is it scored against what up means for that KPI:
 * `isGood = (direction === 'up') === kpi.upIsGood`. A rise in problem sagas
 * therefore reads red, and its arrow still points up.
 *
 * A KPI with no `upIsGood` is neither good nor bad: an average transaction that
 * grew by 8% is not news in either direction, so the card stays neutral whichever
 * way the value moved — the arrow still reports the direction.
 */
export function trendTone(source: TrendSource, trend: KpiTrend | null | undefined): TrendTone {
  const direction = trendDirection(trend);
  if (direction === null || direction === 'flat') return 'neutral';
  if (source.upIsGood === undefined) return 'neutral';
  const isGood = (direction === 'up') === source.upIsGood;
  return isGood ? 'good' : 'bad';
}

/**
 * Whether the card's value is on the right side of its target.
 *
 * <p>The comparison follows the card's semantics, not the arithmetic: a rate
 * that should not exceed 1% is met at 0.4% and missed at 2%, while a rate that
 * should reach 95% is met at 96% and missed at 94%. `upIsGood` is what says
 * which of the two a card is, so a card with a target and no direction — which
 * would have no way to answer — reports no state.
 *
 * <p>`null` means "no judgement to make": no target on this card, no value to
 * judge (the source failed), or no direction to judge it by. The card then
 * renders no dot at all, rather than a dot that is neither green nor red.
 */
export function thresholdState(source: TrendSource, value: number | null | undefined): ThresholdState | null {
  if (source.target === undefined || source.upIsGood === undefined) return null;
  if (value === null || value === undefined || !Number.isFinite(value)) return null;
  const met = source.upIsGood ? value >= source.target : value <= source.target;
  return met ? 'met' : 'missed';
}

/**
 * Hover text for the threshold dot: the target, in the card's own unit, and
 * which side of it counts as met.
 *
 * <p>Spelled out ("Target: at most 1.00%") rather than shown as a bare number,
 * because a target is only half a rule — without the direction, 95% on a
 * failure-rate card reads as a floor when it is a ceiling.
 */
export function thresholdTooltip(source: TrendSource): string {
  if (source.target === undefined) return '';
  const limit =
    source.prefix +
    source.target.toLocaleString('en-US', {
      minimumFractionDigits: source.decimals,
      maximumFractionDigits: source.decimals
    }) +
    (source.suffix ?? '');
  return (source.upIsGood === false ? 'Target: at most ' : 'Target: at least ') + limit;
}

/** Signed percentage for the trend line, e.g. `+20.02%` or `-12.20%`. */
export function deltaText(trend: KpiTrend | null | undefined): string {
  const delta = trend?.deltaPercent;
  if (typeof delta !== 'number' || !Number.isFinite(delta)) return '';
  const sign = delta > 0 ? '+' : delta < 0 ? '-' : '';
  return sign + Math.abs(delta).toFixed(2) + '%';
}

/** Hover text: the exact comparison behind the percentage. */
export function trendTooltip(source: TrendSource, trend: KpiTrend | null | undefined): string {
  if (!trend) return 'Trend unavailable';
  if (!hasTrendPercentage(trend)) {
    return source.noBaselineNote ?? 'No value 7 days ago, so there is no percentage to show';
  }
  const format = (value: number) =>
    source.prefix +
    Number(value).toLocaleString('en-US', {
      minimumFractionDigits: source.decimals,
      maximumFractionDigits: source.decimals
    }) +
    (source.suffix ?? '');
  const period = source.trendTitle ? source.trendTitle + '. ' : '';
  const before = source.previousLabel ?? ' a week ago';
  return period + format(trend.previous) + before + ', ' + format(trend.current) + ' now';
}

/**
 * Daily series for a card's sparkline, oldest first.
 *
 * The last point is replaced with the value the card is showing, so the line
 * ends on the number printed next to it: the server's series and the card's
 * value come from different reads (the server sums the whole ledger, the card
 * only the page it loaded), and a chart that visibly contradicts its own
 * headline number is worse than one point of drift.
 *
 * A `null` in the history becomes a `NaN` rather than a zero, because the
 * sparkline drops non-finite points and would otherwise draw a gap as a value
 * of zero. That distinction is the whole reason the history is nullable: the
 * SLA card's earlier days are missing readings, not a week of answering nothing
 * in time. If too few points survive to draw a line the sparkline renders a
 * dash, which is the honest answer when there is no history yet.
 */
export function sparklineSeries(trend: KpiTrend | null | undefined, currentValue: number | null): number[] | null {
  const history = trend?.history;
  if (!history?.length) return null;
  const series = history.map((value) => (value === null || value === undefined ? NaN : Number(value)));
  if (currentValue !== null && Number.isFinite(currentValue)) {
    series[series.length - 1] = currentValue;
  }
  return series;
}
