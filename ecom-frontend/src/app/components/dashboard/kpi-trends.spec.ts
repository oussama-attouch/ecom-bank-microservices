import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MessageService } from 'primeng/api';
import { DashboardComponent, DashboardRange, KpiCard } from './dashboard.component';
import { KpiTrend, KpiTrends } from '../../models';
import {
  deltaText,
  hasTrendPercentage,
  sparklineSeries,
  thresholdState,
  thresholdTooltip,
  trendDirection,
  trendTone,
  trendTooltip,
  TrendSource
} from './kpi-trends';

/** A trend payload as the endpoint returns it. */
function trend(deltaPercent: number | null, previous: number, current = previous): KpiTrend {
  return {
    current,
    previous,
    deltaPercent,
    history: [previous, previous, previous, previous, previous, previous, current]
  };
}

/** Just the card fields the rules read. */
const upIsGood: TrendSource = { upIsGood: true, prefix: '$', decimals: 2 };
const upIsBad: TrendSource = { upIsGood: false, prefix: '', decimals: 0 };

describe('KPI trend rules (brief 5.3)', () => {
  describe('direction', () => {    it('reads a positive delta as up and a negative one as down', () => {
      expect(trendDirection(trend(20.02, 100, 120))).toBe('up');
      expect(trendDirection(trend(-12.2, 100, 88))).toBe('down');
    });

    it('treats a change too small to display as flat, not as a rise', () => {
      // The delta is rendered to two decimals, so anything under 0.01% would
      // print as "0.00%" next to an arrow that claims a direction.
      expect(trendDirection(trend(0, 100))).toBe('flat');
      expect(trendDirection(trend(0.004, 100))).toBe('flat');
      expect(trendDirection(trend(-0.004, 100))).toBe('flat');
      expect(trendDirection(trend(0.01, 100, 100.01))).toBe('up');
    });

    it('reports no measurement when the delta is withheld', () => {
      // The backend sends null rather than dividing by a zero baseline.
      expect(trendDirection(trend(null, 0, 5000))).toBeNull();
      expect(trendDirection(null)).toBeNull();
      expect(trendDirection(undefined)).toBeNull();
    });
  });

  describe('whether a percentage can be shown', () => {
    it('is true for a real percentage', () => {
      expect(hasTrendPercentage(trend(20.02, 4200, 5040))).toBe(true);
      expect(hasTrendPercentage(trend(-12.2, 4200, 3688))).toBe(true);
    });

    it('is false when the value a week ago was zero, even though the delta is 0%', () => {
      // 0-against-0 is computationally 0%, but "0.00% From last week" would
      // claim a measurement for a KPI that has never held a value.
      expect(hasTrendPercentage(trend(0, 0, 0))).toBe(false);
      expect(hasTrendPercentage(trend(null, 0, 8830))).toBe(false);
    });

    it('is false when the trend endpoint did not answer', () => {
      expect(hasTrendPercentage(null)).toBe(false);
    });
  });

  describe('semantic tone', () => {
    it('greens a rise in a KPI where up is good', () => {
      expect(trendTone(upIsGood, trend(19.05, 4200, 5000))).toBe('good');
      expect(trendTone(upIsGood, trend(100, 1, 2))).toBe('good');
    });

    it('reds a fall in a KPI where up is good', () => {
      expect(trendTone(upIsGood, trend(-12.2, 5000, 4390))).toBe('bad');
    });

    it('reds a RISE in a KPI where up is bad — the arrow still points up', () => {
      // Problem sagas: more of them is worse news, so the colour inverts while
      // the direction does not.
      const rising = trend(25, 4, 5);
      expect(trendDirection(rising)).toBe('up');
      expect(trendTone(upIsBad, rising)).toBe('bad');
    });

    it('greens a fall in a KPI where up is bad', () => {
      const falling = trend(-50, 4, 2);
      expect(trendDirection(falling)).toBe('down');
      expect(trendTone(upIsBad, falling)).toBe('good');
    });

    it('is neutral for no change and for no measurement', () => {
      expect(trendTone(upIsGood, trend(0, 4200))).toBe('neutral');
      expect(trendTone(upIsGood, trend(null, 0, 4200))).toBe('neutral');
      expect(trendTone(upIsGood, null)).toBe('neutral');
    });

    it('stays neutral for a KPI whose movement is neither good nor bad', () => {
      // The brief's neutral row: a bigger average transaction is not a win, and
      // neither is a bigger count of large transfers. The arrow still points the
      // way the value moved; only the colour declines to judge it.
      const directionless: TrendSource = { prefix: '$', decimals: 2 };
      const rising = trend(25, 100, 125);
      expect(trendDirection(rising)).toBe('up');
      expect(trendTone(directionless, rising)).toBe('neutral');
      expect(trendTone(directionless, trend(-25, 100, 75))).toBe('neutral');
    });
  });

  describe('threshold dots', () => {
    it('greens a value on the right side of a floor', () => {
      const floor: TrendSource = { upIsGood: true, prefix: '', suffix: '%', decimals: 2, target: 95 };
      expect(thresholdState(floor, 96.18)).toBe('met');
      expect(thresholdState(floor, 95)).toBe('met'); // exactly on target is met
      expect(thresholdState(floor, 94.99)).toBe('missed');
    });

    it('greens a value on the right side of a ceiling', () => {
      // The comparison follows the card's semantics, not the arithmetic: 0.4%
      // anomalies is good news on a card whose target is "at most 1%".
      const ceiling: TrendSource = { upIsGood: false, prefix: '', suffix: '%', decimals: 2, target: 1 };
      expect(thresholdState(ceiling, 0.4)).toBe('met');
      expect(thresholdState(ceiling, 1)).toBe('met');
      expect(thresholdState(ceiling, 2.1)).toBe('missed');
    });

    it('reads a duration target in the card own unit', () => {
      const millis: TrendSource = { upIsGood: false, prefix: '', suffix: ' ms', decimals: 0, target: 500 };
      expect(thresholdState(millis, 248)).toBe('met');
      expect(thresholdState(millis, 812)).toBe('missed');
    });

    it('has no state for a card without a target, a value, or a direction', () => {
      // No target: most of the sixteen measure size rather than pass/fail.
      expect(thresholdState(upIsGood, 5000)).toBeNull();
      // A target but no value to judge — the source failed, so there is nothing
      // to be right or wrong about and the card renders no dot at all.
      const floored: TrendSource = { upIsGood: true, prefix: '', decimals: 0, target: 95 };
      expect(thresholdState(floored, null)).toBeNull();
      expect(thresholdState(floored, undefined)).toBeNull();
      expect(thresholdState(floored, NaN)).toBeNull();
      // A target with no direction cannot say which side is the good one.
      const directionless: TrendSource = { prefix: '', decimals: 0, target: 95 };
      expect(thresholdState(directionless, 96)).toBeNull();
    });

    it('spells the target out in the card unit, direction included', () => {
      const ceiling: TrendSource = { upIsGood: false, prefix: '', suffix: '%', decimals: 2, target: 1 };
      expect(thresholdTooltip(ceiling)).toBe('Target: at most 1.00%');

      const floor: TrendSource = { upIsGood: true, prefix: '', suffix: '%', decimals: 1, target: 95 };
      expect(thresholdTooltip(floor)).toBe('Target: at least 95.0%');

      const millis: TrendSource = { upIsGood: false, prefix: '', suffix: ' ms', decimals: 0, target: 1000 };
      expect(thresholdTooltip(millis)).toBe('Target: at most 1,000 ms');

      const money: TrendSource = { upIsGood: true, prefix: '$', decimals: 2, target: 4200 };
      expect(thresholdTooltip(money)).toBe('Target: at least $4,200.00');
    });
  });

  describe('delta text', () => {    it('signs the value and always keeps two decimals', () => {
      expect(deltaText(trend(20.02, 100, 120))).toBe('+20.02%');
      expect(deltaText(trend(-12.2, 100, 88))).toBe('-12.20%');
      expect(deltaText(trend(0, 100))).toBe('0.00%');
      expect(deltaText(trend(100, 1, 2))).toBe('+100.00%');
    });

    it('is empty when there is no delta, so the card renders a dash instead', () => {
      expect(deltaText(trend(null, 0, 5000))).toBe('');
      expect(deltaText(null)).toBe('');
    });
  });

  describe('tooltip', () => {
    it('spells out the comparison, formatted like the card', () => {
      expect(trendTooltip(upIsGood, trend(19.05, 4200, 5000))).toBe('$4,200.00 a week ago, $5,000.00 now');
    });

    it('honours the card decimals for count KPIs', () => {
      expect(trendTooltip(upIsBad, trend(100, 1, 2))).toBe('1 a week ago, 2 now');
    });

    it('leads with the period note for windowed KPIs', () => {
      const windowed: TrendSource = { ...upIsBad, trendTitle: 'Started in the last 7 days' };
      expect(trendTooltip(windowed, trend(100, 1, 2))).toBe('Started in the last 7 days. 1 a week ago, 2 now');
    });

    it('carries the card unit through to the comparison', () => {
      // The rate and duration cards read as a percentage or a duration, and the
      // tooltip has to say so or "32.49 a week ago" means nothing.
      const percent: TrendSource = { upIsGood: false, prefix: '', suffix: '%', decimals: 2 };
      expect(trendTooltip(percent, trend(-0.8, 32.49, 32.23))).toBe('32.49% a week ago, 32.23% now');

      const millis: TrendSource = { upIsGood: false, prefix: '', suffix: ' ms', decimals: 0 };
      expect(trendTooltip(millis, trend(1.85, 552, 562))).toBe('552 ms a week ago, 562 ms now');
    });

    it('explains a missing percentage and a missing payload', () => {
      expect(trendTooltip(upIsGood, trend(null, 0, 5000))).toContain('No value 7 days ago');
      expect(trendTooltip(upIsGood, null)).toBe('Trend unavailable');
    });
  });

  describe('sparkline series', () => {
    it('passes the daily history through, oldest first', () => {
      const series = sparklineSeries({ ...trend(10, 100, 110), history: [1, 2, 3, 4, 5, 6, 110] }, null);
      expect(series).toEqual([1, 2, 3, 4, 5, 6, 110]);
    });

    it('ends on the value the card is showing', () => {
      // The card's number and the server's series come from different reads, so
      // the last point is snapped to the headline value.
      const series = sparklineSeries({ ...trend(10, 100, 110), history: [1, 2, 3, 4, 5, 6, 110] }, 9166.67);
      expect(series).toEqual([1, 2, 3, 4, 5, 6, 9166.67]);
    });

    it('is null when there is no history to draw', () => {
      expect(sparklineSeries(null, 10)).toBeNull();
      expect(sparklineSeries({ ...trend(10, 100), history: [] }, 10)).toBeNull();
    });

    it('renders nothing when the card has no value and no history', () => {
      expect(sparklineSeries({ ...trend(null, 0), history: [] }, null)).toBeNull();
    });

    it('turns a null day into a gap rather than a zero', () => {
      // The SLA card's earlier days have no reading at all, because the samples
      // live in the server's memory. A zero-fill would draw a week of answering
      // nothing in time; a NaN is dropped by the sparkline as the gap it is.
      const series = sparklineSeries({ ...trend(10, 100, 110), history: [null, null, 1, 2, 3, 4, 110] }, null);
      expect(series!.slice(0, 2).every((v) => Number.isNaN(v))).toBe(true);
      expect(series!.slice(2)).toEqual([1, 2, 3, 4, 110]);
    });

    it('leaves a single real point as nothing to draw', () => {
      // Six gaps and today: one point is not a line, and the sparkline shows a
      // dash rather than a flat mark that would read as "no change".
      const series = sparklineSeries(
        { ...trend(10, 100, 110), history: [null, null, null, null, null, null, 110] },
        null
      );
      expect(series!.filter((v) => Number.isFinite(v)).length).toBe(1);
    });
  });
});

describe('DashboardComponent KPI cards', () => {
  let component: DashboardComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService]
    }).compileComponents();

    // Built without detectChanges on purpose: this suite is about the trend
    // rules the cards read, and ngOnInit would start the 5s HTTP poll.
    component = TestBed.createComponent(DashboardComponent).componentInstance;
  });

  /** Builds a card the way `kpis` does, for the rules to read. */
  const card = (key: keyof KpiTrends, upIsGood: boolean, decimals = 0, prefix = ''): KpiCard => ({
    label: key,
    icon: '',
    key,
    upIsGood,
    value: 5,
    prefix,
    decimals
  });

  /** The sixteen keys the endpoint sends, in card order. */
  const KPI_KEYS: (keyof KpiTrends)[] = [
    'assetsUnderManagement', 'activeAccounts', 'todayVolume', 'problemSagas',
    'netCashFlow', 'avgTransactionValue', 'balanceConcentration', 'largeTransfers',
    'sagaSuccessRate', 'avgSagaDuration', 'p95SagaDuration', 'compensationRate',
    'sagaJournalReconciliation', 'anomalyRate', 'auditTrailCompleteness', 'dashboardSlaCompliance'
  ];

  /**
   * A full payload with every KPI moving by `delta` percent.
   *
   * Built key by key rather than with `Object.fromEntries`, so the compiler
   * checks that this fixture really does cover every KPI the cards read: a
   * missing key here would otherwise surface as a card stuck on "No data".
   */
  const allTrends = (delta: number): KpiTrends => {
    const moved = trend(delta, 100, 100 + delta);
    return {
      assetsUnderManagement: moved, activeAccounts: moved, todayVolume: moved, problemSagas: moved,
      netCashFlow: moved, avgTransactionValue: moved, balanceConcentration: moved, largeTransfers: moved,
      sagaSuccessRate: moved, avgSagaDuration: moved, p95SagaDuration: moved, compensationRate: moved,
      sagaJournalReconciliation: moved, anomalyRate: moved, auditTrailCompleteness: moved,
      dashboardSlaCompliance: moved
    };
  };

  /** Each card's tone, keyed the way the card reads its own trend. */
  const tones = (): Map<keyof KpiTrends, string> =>
    new Map(component.kpis.map((k) => [k.key, component.kpiTone(k)]));

  it('gives each card its own trend, keyed by name rather than position', () => {
    component.trends = {
      assetsUnderManagement: trend(19.05, 4200, 5000),
      activeAccounts: trend(100, 1, 2),
      todayVolume: trend(null, 0, 8830),
      problemSagas: trend(25, 4, 5)
    } as KpiTrends;

    expect(component.kpiDelta(card('assetsUnderManagement', true, 2, '$'))).toBe('+19.05%');
    expect(component.kpiDelta(card('activeAccounts', true))).toBe('+100.00%');
    expect(component.kpiHasTrend(card('todayVolume', true, 2, '$'))).toBe(false);
    expect(component.kpiTone(card('problemSagas', false))).toBe('bad');
  });

  it('shows a dash rather than a percentage until the trend endpoint answers', () => {
    component.trends = null;

    expect(component.kpiHasTrend(card('assetsUnderManagement', true, 2, '$'))).toBe(false);
    expect(component.kpiTone(card('assetsUnderManagement', true, 2, '$'))).toBe('neutral');
    expect(component.kpiDelta(card('assetsUnderManagement', true, 2, '$'))).toBe('');
    expect(component.kpiSparkline(card('assetsUnderManagement', true, 2, '$'))).toBeNull();
  });

  it('lays out sixteen cards: cumulative, financial, operational, governance', () => {
    // Rows are the grid's order, so this is the layout: four to a row, four
    // rows, with the new KPIs in the order the brief lists them. The default
    // range is 30D, and the four cards whose value is a window name it in their
    // label; the rest read the same on every range.
    expect(component.kpis.map((k) => k.label)).toEqual([
      'Assets Under Management', 'Active Accounts', 'Volume (30d)', 'Problem Sagas',
      'Net Cash Flow (30d)', 'Avg Transaction Value (30d)', 'Balance Concentration (Top 10)', 'Large Transfers (30d, >$10K)',
      'Saga Success Rate', 'Avg Saga Duration', 'P95 Saga Duration', 'Compensation Rate',
      'Saga–Journal Reconciliation', 'Anomaly Rate (3σ)', 'Audit Trail Completeness', 'Dashboard SLA (>95%)'
    ]);
    expect(component.kpis.map((k) => k.key)).toEqual(KPI_KEYS);
  });

  it('puts a target on exactly the nine cards that have a pass or fail', () => {
    // The other seven measure size — assets, accounts, volume, flow, average
    // transaction, large transfers, average duration — and have no target a dot
    // could judge them against.
    const targeted = component.kpis.filter((k) => k.target !== undefined);
    expect(targeted.map((k) => k.key)).toEqual([
      'problemSagas', 'balanceConcentration', 'sagaSuccessRate', 'p95SagaDuration', 'compensationRate',
      'sagaJournalReconciliation', 'anomalyRate', 'auditTrailCompleteness', 'dashboardSlaCompliance'
    ]);
    expect(targeted.map((k) => k.target)).toEqual([0, 80, 95, 1000, 5, 100, 1, 100, 95]);
    // A target is only half a rule; every targeted card must also say which side
    // of it counts as good, or its dot would have nothing to colour by.
    expect(targeted.every((k) => k.upIsGood !== undefined)).toBe(true);
  });

  it('scopes each governance card to the window its number actually covers', () => {
    const cards = new Map(component.kpis.map((k) => [k.key, k]));
    // The rates read as percentages at two decimals; the SLA at one, because a
    // tenth of a percent is the resolution its 24-hour window supports.
    expect(cards.get('sagaJournalReconciliation')!.suffix).toBe('%');
    expect(cards.get('anomalyRate')!.suffix).toBe('%');
    expect(cards.get('auditTrailCompleteness')!.suffix).toBe('%');
    expect(cards.get('dashboardSlaCompliance')!.suffix).toBe('%');
    expect(cards.get('dashboardSlaCompliance')!.decimals).toBe(1);
    // The SLA card compares 24 hours and the concentration card its own seven
    // days, whatever the selector says, so those two are the ones that override
    // the period label; every other card names the selected range.
    expect(cards.get('dashboardSlaCompliance')!.trendPeriod).toBe('From previous 24h');
    expect(cards.get('balanceConcentration')!.trendPeriod).toBe('From last week');
    expect(cards.get('anomalyRate')!.trendPeriod).toBe('From previous 30d');
  });

  describe('the period selector', () => {
    const cardOf = (key: keyof KpiTrends) =>
      component.kpis.find((k) => k.key === key)!;

    it('names the selected range on every windowed card', () => {
      for (const [range, token, over, previous] of [
        ['7D', '7d', 'Over 7 days', 'From previous 7d'],
        ['30D', '30d', 'Over 30 days', 'From previous 30d'],
        ['90D', '90d', 'Over 90 days', 'From previous 90d'],
        ['1Y', '1y', 'Over 1 year', 'From previous 1y']
      ] as [DashboardRange, string, string, string][]) {
        component.range = range;
        expect(cardOf('todayVolume').label).withContext(range).toBe(`Volume (${token})`);
        expect(cardOf('netCashFlow').label).withContext(range).toBe(`Net Cash Flow (${token})`);
        expect(cardOf('avgTransactionValue').label).withContext(range).toBe(`Avg Transaction Value (${token})`);
        expect(cardOf('largeTransfers').label).withContext(range).toBe(`Large Transfers (${token}, >$10K)`);
        // The subtitle and the trend row spell the same window in prose.
        expect(cardOf('todayVolume').subtitle).withContext(range).toBe(over);
        expect(cardOf('anomalyRate').trendPeriod).withContext(range).toBe(previous);
        // Rates and durations keep their own names: the window is the subtitle.
        expect(cardOf('sagaSuccessRate').label).withContext(range).toBe('Saga Success Rate');
      }
    });

    it('leaves the snapshot cards out of the subtitle, and ALL without a comparison', () => {
      component.range = 'ALL';

      // A level is "now" on every range, so there is no window to name — and
      // nothing before the whole log to compare against, so the trend row shows
      // its dash and no label rather than a period that was never measured.
      expect(cardOf('assetsUnderManagement').subtitle).toBeUndefined();
      expect(cardOf('activeAccounts').subtitle).toBeUndefined();
      expect(cardOf('balanceConcentration').subtitle).toBeUndefined();
      expect(cardOf('assetsUnderManagement').trendPeriod).toBe('');
      expect(cardOf('todayVolume').trendPeriod).toBe('');
      expect(cardOf('todayVolume').label).toBe('Volume (all)');
      expect(cardOf('todayVolume').subtitle).toBe('Over all time');
      expect(cardOf('todayVolume').noBaselineNote).toContain('no earlier period');
    });

    it('anchors a sparkline to its card value only where the scales match', () => {
      // A flow card's value is the whole window while its sparkline plots one
      // point per bucket: pinning the last point to the total would draw a spike
      // the width of the range. Rates, means and levels share the point's scale.
      component.trends = {
        todayVolume: { current: 3000, previous: 1000, deltaPercent: 200, history: [1, 2, 3] }
      } as KpiTrends;

      expect(component.kpiSparkline(cardOf('todayVolume'))).toEqual([1, 2, 3]);
    });
  });

  it('reads the eight windowed cards from the payload, and blanks them without it', () => {
    component.trends = allTrends(25);

    const cards = new Map(component.kpis.map((k) => [k.key, k]));
    // The value shown is the server's `current`: a 7-day window over the whole
    // ledger is not derivable from the page of rows the browser holds.
    expect(cards.get('netCashFlow')!.value).toBe(125);
    expect(cards.get('p95SagaDuration')!.value).toBe(125);
    expect(cards.get('netCashFlow')!.suffix).toBeUndefined();
    expect(cards.get('compensationRate')!.suffix).toBe('%');
    expect(cards.get('avgSagaDuration')!.suffix).toBe(' ms');

    component.trends = null;
    expect(new Map(component.kpis.map((k) => [k.key, k])).get('netCashFlow')!.value).toBeNull();
  });

  it('colours every rising KPI by what a rise means for it', () => {
    component.trends = allTrends(25);
    const rising = tones();

    expect(rising.size).toBe(16);
    expect(rising.get('netCashFlow')).toBe('good');            // money in is the point
    expect(rising.get('avgTransactionValue')).toBe('neutral'); // neither good nor bad
    expect(rising.get('balanceConcentration')).toBe('bad');    // fewer accounts holding more
    expect(rising.get('largeTransfers')).toBe('neutral');
    expect(rising.get('sagaSuccessRate')).toBe('good');
    expect(rising.get('avgSagaDuration')).toBe('bad');         // slower is worse
    expect(rising.get('p95SagaDuration')).toBe('bad');
    expect(rising.get('compensationRate')).toBe('bad');
    // Governance: more reconciled transfers and more complete audit trails are
    // wins; more statistical anomalies are not.
    expect(rising.get('sagaJournalReconciliation')).toBe('good');
    expect(rising.get('auditTrailCompleteness')).toBe('good');
    expect(rising.get('dashboardSlaCompliance')).toBe('good');
    expect(rising.get('anomalyRate')).toBe('bad');
  });

  it('inverts that table for every falling KPI', () => {
    component.trends = allTrends(-25);
    const falling = tones();

    expect(falling.get('netCashFlow')).toBe('bad');
    expect(falling.get('balanceConcentration')).toBe('good');
    expect(falling.get('sagaSuccessRate')).toBe('bad');
    expect(falling.get('avgSagaDuration')).toBe('good');
    expect(falling.get('p95SagaDuration')).toBe('good');
    expect(falling.get('compensationRate')).toBe('good');
    expect(falling.get('sagaJournalReconciliation')).toBe('bad');
    expect(falling.get('anomalyRate')).toBe('good');
    expect(falling.get('auditTrailCompleteness')).toBe('bad');
    expect(falling.get('dashboardSlaCompliance')).toBe('bad');
    // Neutral is neutral whichever way it moved.
    expect(falling.get('avgTransactionValue')).toBe('neutral');
    expect(falling.get('largeTransfers')).toBe('neutral');
  });

  it('judges each targeted card against its own target, not its trend', () => {
    component.trends = allTrends(25);
    const cards = new Map(component.kpis.map((k) => [k.key, k]));

    // Every card's value is 125 on this fixture, so which side of its target it
    // falls on differs per card: 125 reconciled transfers clears 100%, 125ms
    // clears the 1000ms ceiling, but a 125% compensation rate does not clear 5%.
    expect(component.kpiTargetState(cards.get('sagaJournalReconciliation')!)).toBe('met');
    expect(component.kpiTargetState(cards.get('p95SagaDuration')!)).toBe('met');
    expect(component.kpiTargetState(cards.get('anomalyRate')!)).toBe('missed');
    expect(component.kpiTargetState(cards.get('compensationRate')!)).toBe('missed');
    expect(component.kpiTargetState(cards.get('netCashFlow')!)).toBeNull(); // no target
  });

  it('shows no dot while the cards are still loading', () => {
    // The skeleton pass has no values yet, so a dot would be a judgement on a
    // number that has not arrived.
    component.trends = null;
    const cards = new Map(component.kpis.map((k) => [k.key, k]));

    expect(component.kpiTargetState(cards.get('sagaSuccessRate')!)).toBeNull();
    expect(component.kpiTargetTooltip(cards.get('sagaSuccessRate')!)).toBe('Target: at least 95.00%');
  });
});
