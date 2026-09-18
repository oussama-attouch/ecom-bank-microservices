import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MessageService } from 'primeng/api';
import { DashboardComponent, DashboardRange } from './dashboard.component';
import { LedgerService } from '../../services/ledger.service';
import { JournalService } from '../../services/journal.service';
import { ChartSeries } from '../../models';
import { NEVER, of } from 'rxjs';

/**
 * When the two range-dependent payloads are fetched, per refresh reason.
 *
 * This is the bug the range selector shipped with: the fetch was gated on
 * `initialLoad || isPollRange(token)`. Once the first load had landed,
 * initialLoad was spent and 90d/1y/all are not poll ranges, so those clicks
 * fetched nothing at all — neither on the click nor on any later poll — and the
 * charts sat on "No data" for the rest of the session. 7D and 30D hid it,
 * because their poll re-fetched them every 5 seconds regardless.
 *
 * The KPI trends follow the same policy for the same reason: they are the same
 * window over the same ledger, so the cards must be re-read when the charts are
 * and left alone when they are. The three rules, one test each:
 *   - mount:         fetch the saved range once, for both payloads
 *   - range change:  always fetch, whatever the range costs
 *   - poll tick:     only 7d/30d; the long ranges keep what they have
 *
 * The two are stubbed rather than exercised over HTTP: the question here is
 * *whether* the component asks for them, and stubbing makes the call log the
 * record of that. The requests themselves are covered by the ledger service and
 * by the browser pass.
 */

const SERIES: ChartSeries = {
  range: '90d',
  dailyVolume: [{ date: '2026-09-17', count: 3 }],
  dailyFlow: [{ date: '2026-09-17', in: 10, out: 4 }],
  balanceDistribution: [],
  sagaBreakdown: { completed: 1, compensating: 0, failed: 0 }
};

/** A payload with one KPI in it: enough for the cards to have something to hold. */
const TRENDS = { todayVolume: { current: 1234, previous: 1000, deltaPercent: 23.4, history: [1, 2, 3] } } as any;

describe('DashboardComponent range-payload fetch policy', () => {
  let component: DashboardComponent;
  let seriesSpy: jasmine.Spy;
  let trendsSpy: jasmine.Spy;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService]
    }).compileComponents();

    component = TestBed.createComponent(DashboardComponent).componentInstance;
    const ledger = TestBed.inject(LedgerService);
    const journal = TestBed.inject(JournalService);

    // Every other source is stubbed too, so nothing but the calls under test can
    // reach the network and the assertions cannot be confounded.
    spyOn(ledger, 'listAccounts').and.returnValue(of([]));
    spyOn(ledger, 'listSagas').and.returnValue(of([]));
    spyOn(journal, 'entries').and.returnValue(of([]));
    spyOn(journal, 'trialBalance').and.returnValue(of({ totalDebits: 0, totalCredits: 0, balanced: true }));
    // Both answer for whatever range they are asked for, so the component can settle.
    trendsSpy = spyOn(ledger, 'kpiTrends').and.callFake((range: string) => of({ ...TRENDS, range }));
    seriesSpy = spyOn(ledger, 'getChartSeries').and.callFake((range: string) => of({ ...SERIES, range }));
  });

  afterEach(() => {
    component.ngOnDestroy();
    localStorage.clear();
  });

  /** The `range` argument of every chart-series call so far. */
  const fetchedRanges = (): string[] => seriesSpy.calls.allArgs().map((args) => args[0] as string);

  /** The same for the KPI trends. */
  const trendRanges = (): string[] => trendsSpy.calls.allArgs().map((args) => args[0] as string);

  it('fetches the saved range on mount, for the cards as well as the charts', () => {
    localStorage.setItem('dashboard.range', '90D');
    component.ngOnInit();

    expect(fetchedRanges()).toEqual(['90d']);
    expect(trendRanges()).toEqual(['90d']);
    expect(component.chartSeries?.range).toBe('90d');
    expect(component.trends?.todayVolume?.current).toBe(1234);
    expect(component.volumeChart).not.toBeNull();
  });

  it('marks the first load finished, which is what used to disable the fetch', () => {
    component.ngOnInit();

    // Guards the assumption the policy must never rest on again: initialLoad is
    // spent after the first response, so no user-initiated fetch may depend on it.
    expect(component.initialLoad).toBe(false);
  });

  it('fetches a new range on the range change, even though the poll would skip it', () => {
    component.ngOnInit();
    component.initialLoad = false;       // the app is in its settled state

    component.range = '90D';
    component.onRangeChange();

    // The regression: this used to issue nothing, because initialLoad was false
    // and 90d is not a poll range.
    expect(fetchedRanges()).toEqual(['30d', '90d']);
    expect(trendRanges()).toEqual(['30d', '90d']);
    expect(component.chartSeries?.range).toBe('90d');
  });

  it('fetches every expensive range on its click, not only the cheap ones', () => {
    component.ngOnInit();
    component.initialLoad = false;

    for (const range of ['90D', '1Y', 'ALL'] as DashboardRange[]) {
      component.range = range;
      component.onRangeChange();
      expect(component.chartSeries?.range)
        .withContext(`${range} should have fetched its own series`)
        .toBe(range.toLowerCase());
      expect(component.trends)
        .withContext(`${range} should have fetched its own KPI payload`)
        .not.toBeNull();
    }

    expect(fetchedRanges()).toEqual(['30d', '90d', '1y', 'all']);
    expect(trendRanges()).toEqual(['30d', '90d', '1y', 'all']);
  });

  it('does not re-fetch the long ranges on a poll tick, for either payload', () => {
    component.ngOnInit();
    component.range = '90D';
    component.onRangeChange();
    const seriesBefore = fetchedRanges().length;
    const trendsBefore = trendRanges().length;

    component.refreshAll('poll');
    component.refreshAll('poll');

    expect(fetchedRanges().length).toBe(seriesBefore);
    expect(trendRanges().length).toBe(trendsBefore);
    // What the skipped fetches leave in place survives them, and still draws:
    // 91 daily rows roll up into 13 weekly points.
    expect(component.chartSeries?.range).toBe('90d');
    expect(component.trends?.todayVolume?.current).toBe(1234);
    expect(component.volumeChart).not.toBeNull();
    expect(component.volumeChart.datasets[0].data.length).toBe(13);
  });

  it('keeps re-fetching the short ranges on a poll tick', () => {
    component.ngOnInit();
    expect(component.range).toBe('30D');
    expect(fetchedRanges()).toEqual(['30d']);
    expect(trendRanges()).toEqual(['30d']);

    component.refreshAll('poll');
    component.refreshAll('poll');

    expect(fetchedRanges()).toEqual(['30d', '30d', '30d']);
    expect(trendRanges()).toEqual(['30d', '30d', '30d']);
  });

  /**
   * The cards are dropped the moment the range changes, like the charts: a
   * 7-day volume under a "Volume (1y)" label is exactly the mislabelling this
   * whole policy exists to prevent.
   */
  it('blanks the range payloads while the new range is still in flight', () => {
    component.ngOnInit();
    expect(component.trends?.todayVolume?.current).toBe(1234);

    trendsSpy.and.returnValue(NEVER);
    seriesSpy.and.returnValue(NEVER);
    component.range = '1Y';
    component.onRangeChange();

    expect(component.trends).toBeNull();
    expect(component.chartSeries).toBeNull();
  });
});
