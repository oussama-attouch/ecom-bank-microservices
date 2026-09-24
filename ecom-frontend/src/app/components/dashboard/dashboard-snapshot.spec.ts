import { ComponentFixture, TestBed, discardPeriodicTasks, fakeAsync, tick } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';
import { MessageService } from 'primeng/api';
import { Observable, Subject, of } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { TimelineScrubberComponent } from './timeline-scrubber/timeline-scrubber.component';
import { LedgerService } from '../../services/ledger.service';
import { JournalService } from '../../services/journal.service';
import { Account, ChartSeries, KpiTrend, KpiTrends } from '../../models';

/**
 * Snapshot mode: the instant the dashboard describes, and what the cards show.
 *
 * <p>These pin the two bugs that reached the browser from the first cut of the
 * scrubber. Both had one root cause: the scrubber's output was named `change`,
 * which is also a native DOM event, so the inner range input's own `change`
 * bubbled to the component's host and Angular delivered that `Event` to the same
 * binding as the output. The dashboard received a DOM event where it expected a
 * `Date`; the URL builder threw on the junk instant; no request was issued; and
 * because the scrub had already cleared the payloads, the two browser-computed
 * cards kept their previous **live** values while every server-driven card read
 * "No data".
 *
 * <p>Neither symptom pointed at that cause, so the assertions are split by
 * symptom as well as by mechanism: one for each bug as it appeared on screen,
 * plus ones for the wiring and the race that produced them.
 */

/** A live ledger: the values the broken snapshot mode was wrongly showing. */
const LIVE_ACCOUNTS: Account[] = [
  { accountId: 'ACC-LIVE-1', balance: 19_309_159.8 } as Account,
  { accountId: 'ACC-LIVE-2', balance: 0 } as Account
];

/** The same ledger at 2025-05-18, at the numbers the backend actually returns. */
const SNAPSHOT_ACCOUNTS: Account[] = [
  { accountId: 'ACC-SNAP-1', balance: 13_634_623.28 } as Account
];

/**
 * The shape the backend returns for range=1y at 2025-05-18: the seed starts in
 * October 2024, so the previous window (2023-05-18 to 2024-05-18) is empty —
 * `previous` is 0 and `deltaPercent` is null — while `current` holds a real
 * value. That combination is what the second bug was reported as.
 */
function trend(current: number): KpiTrend {
  return { current, previous: 0, deltaPercent: null, history: [current] } as unknown as KpiTrend;
}

const SNAPSHOT_TRENDS = {
  assetsUnderManagement: trend(13_634_623.28),
  activeAccounts: trend(192),
  todayVolume: trend(17_352_140.84),
  netCashFlow: trend(13_634_623.28),
  sagaSuccessRate: trend(95.86),
  problemSagas: trend(6),
  largeTransfers: trend(276),
  avgTransactionValue: trend(9319.09)
} as unknown as KpiTrends;

const SERIES: ChartSeries = {
  range: '1y',
  dailyVolume: [{ date: '2025-05-18', count: 3 }],
  dailyFlow: [{ date: '2025-05-18', in: 10, out: 4 }],
  balanceDistribution: [{ accountId: 'ACC-SNAP-1', balance: 13_634_623.28 }],
  sagaBreakdown: { completed: 1, compensating: 0, failed: 0 },
  historyStart: '2024-10-01T03:51:05.354590Z'
};

/** The instant the bug report used. */
const SCRUB_TO = new Date('2025-05-18T10:27:00Z');

describe('DashboardComponent snapshot mode', () => {
  let fixture: ComponentFixture<DashboardComponent>;
  let component: DashboardComponent;
  let ledger: LedgerService;
  let journal: JournalService;
  /** What instant each source was asked about, per call. */
  let accountsAt: (Date | null | undefined)[];
  let trendsAt: (Date | null | undefined)[];

  beforeEach(async () => {
    localStorage.clear();
    localStorage.setItem('dashboard.range', '1Y');
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService]
    }).compileComponents();
  });

  afterEach(() => {
    localStorage.clear();
  });

  /** Mounts the dashboard, with every source answering live or snapshot by `at`. */
  function mount(): ComponentFixture<DashboardComponent> {
    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    ledger = TestBed.inject(LedgerService);
    journal = TestBed.inject(JournalService);

    accountsAt = [];
    trendsAt = [];

    spyOn(ledger, 'listAccounts').and.callFake((silent?: boolean, at?: Date | null) => {
      accountsAt.push(at);
      return of(at ? SNAPSHOT_ACCOUNTS : LIVE_ACCOUNTS);
    });
    spyOn(ledger, 'listSagas').and.returnValue(of([]));
    spyOn(journal, 'entries').and.returnValue(of([]));
    spyOn(journal, 'trialBalance').and.returnValue(of({ totalDebits: 0, totalCredits: 0, balanced: true }));
    spyOn(ledger, 'kpiTrends').and.callFake((r: string, silent?: boolean, at?: Date | null) => {
      trendsAt.push(at);
      return of({ ...SNAPSHOT_TRENDS, range: r });
    });
    spyOn(ledger, 'getChartSeries').and.callFake((r: string, silent?: boolean, at?: Date | null) =>
      of({ ...SERIES, range: r }));

    component.ngOnInit();
    fixture.detectChanges();
    tick();
    fixture.detectChanges();
    return fixture;
  }

  /** Cancels the poll and drains the timers, so fakeAsync sees a clean queue. */
  function settle(): void {
    component.ngOnDestroy();
    tick(2000);
    discardPeriodicTasks();
  }

  /** The card the KPI grid renders for a key, as the template sees it. */
  const card = (key: keyof KpiTrends) => component.kpis.find((k) => k.key === key)!;

  /** The rendered `.kpi` element whose label starts with `label`. */
  function cardElement(label: string): HTMLElement | undefined {
    return Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('.kpi'))
      .find((el) => (el.querySelector('.kpi-label')?.textContent ?? '').startsWith(label)) as HTMLElement;
  }

  it('asks every source about the scrubbed instant, not about now', fakeAsync(() => {
    mount();
    component.onScrub(SCRUB_TO);
    tick(50);

    expect(accountsAt[accountsAt.length - 1]).toBe(SCRUB_TO);
    expect(trendsAt[trendsAt.length - 1]).toBe(SCRUB_TO);
    expect(component.asOf()).toBe(SCRUB_TO);
    settle();
  }));

  /**
   * Bug 1 as it appeared on screen. AUM and Active Accounts are computed in the
   * browser from the accounts array, so they follow the scrubber only through
   * that array — which is exactly why they were the two cards left showing live
   * numbers when the instant never reached the services.
   */
  it('shows the snapshot AUM and account count, not the live ones', fakeAsync(() => {
    mount();
    component.onScrub(SCRUB_TO);
    tick(50);

    expect(card('assetsUnderManagement').value).toBe(13_634_623.28);
    expect(card('activeAccounts').value).toBe(1);
    expect(card('assetsUnderManagement').value).not.toBe(19_309_159.8);
    settle();
  }));

  /**
   * Bug 2 as it appeared on screen, and the behaviour that was asked for: an
   * empty previous window yields a null delta, which belongs on the trend row as
   * a dash. The value is a separate quantity and must still render — "No data" is
   * for a missing `current`, not for a missing comparison.
   */
  it('renders a value with a dash trend when there is no baseline to compare against', fakeAsync(() => {
    mount();
    component.onScrub(SCRUB_TO);
    tick(50);
    fixture.detectChanges();

    const volume = card('todayVolume');
    expect(volume.value).toBe(17_352_140.84);
    // The trend row is driven off deltaPercent, and reads as "no measurement".
    expect(component.kpiHasTrend(volume)).toBe(false);
    expect(component.kpiDelta(volume)).toBe('');

    const el = cardElement('Volume');
    expect(el).withContext('the Volume card should render').toBeTruthy();

    // The card must not be in its placeholder state: that class is what renders
    // "No data", and a null `deltaPercent` must never reach it. The *number* is
    // asserted on the card above rather than read back out of the DOM, because
    // CountUpDirective animates from zero using real performance.now(), which
    // fakeAsync does not advance — the element legitimately still reads $0.00
    // here even though the card's value is correct.
    const valueEl = el!.querySelector('.kpi-value')!;
    expect(valueEl.classList.contains('na'))
      .withContext('a null deltaPercent must not put the card in its No-data state')
      .toBe(false);
    expect(valueEl.textContent).not.toContain('No data');
    expect(el!.querySelector('.trend-none')?.textContent).toContain('—');

    // Every snapshot card with a real `current` renders a number, not the placeholder.
    for (const key of ['netCashFlow', 'sagaSuccessRate', 'largeTransfers', 'problemSagas'] as (keyof KpiTrends)[]) {
      expect(card(key).value).withContext(`${key} should have a value`).not.toBeNull();
    }
    settle();
  }));

  it('returns to live values when the operator leaves snapshot mode', fakeAsync(() => {
    mount();
    component.onScrub(SCRUB_TO);
    tick(50);
    expect(card('assetsUnderManagement').value).toBe(13_634_623.28);

    component.onScrub(null);
    tick(50);

    expect(component.asOf()).toBeNull();
    expect(card('assetsUnderManagement').value).toBe(19_309_159.8);
    expect(accountsAt[accountsAt.length - 1]).toBeNull();
    settle();
  }));

  /**
   * The wiring itself. A native `change` from the inner range input must not
   * reach the dashboard: it carries an `Event`, not an instant, and under the old
   * `(change)` binding it poisoned `asOf()` — after which the URL builder threw
   * and nothing was fetched at all.
   */
  it('turns the inner input\'s native events into a real instant', fakeAsync(() => {
    mount();
    const input = fixture.nativeElement.querySelector('#ledger-scrubber') as HTMLInputElement;
    expect(input).withContext('the slider should render').not.toBeNull();
    const before = accountsAt.length;

    // Exactly what a drag or an arrow-key press produces in a real browser.
    input.value = '10';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();
    tick(400);

    const asOf = component.asOf();
    expect(asOf instanceof Date)
      .withContext(`asOf must be a Date, got ${Object.prototype.toString.call(asOf)}`)
      .toBe(true);
    expect(Number.isNaN(asOf!.getTime())).toBe(false);
    // And it was carried through to the fetch.
    expect(accountsAt.length).toBeGreaterThan(before);
    expect(accountsAt[accountsAt.length - 1]).toBe(asOf!);
    settle();
  }));

  it('refuses a non-Date instant rather than poisoning the fetch', fakeAsync(() => {
    mount();
    const before = accountsAt.length;

    // What a mis-wired binding delivers: a native event, or a junk Date.
    component.onScrub(new Event('change') as unknown as Date);
    component.onScrub(new Date('nonsense'));
    tick(50);

    expect(component.asOf()).toBeNull();
    expect(accountsAt.length).withContext('no request should be issued').toBe(before);
    settle();
  }));

  /**
   * The race the bug was hiding behind. The accounts array was assigned without a
   * generation check, so a live reply still in flight when the operator scrubs
   * landed last and put the live balances back under the snapshot banner. Every
   * source is gated now, so a superseded reply is discarded.
   */
  it('does not let a superseded live reply overwrite the snapshot', fakeAsync(() => {
    mount();

    // A live refresh whose reply is held open, then superseded by a scrub.
    let releaseLive: (accounts: Account[]) => void = () => { };
    (ledger.listAccounts as jasmine.Spy).and.callFake((silent?: boolean, at?: Date | null) => {
      if (at) return of(SNAPSHOT_ACCOUNTS);
      return new Observable<Account[]>((subscriber) => {
        releaseLive = (accounts) => {
          subscriber.next(accounts);
          subscriber.complete();
        };
      });
    });

    component.refreshAll('poll');
    component.onScrub(SCRUB_TO);
    tick(50);
    expect(card('assetsUnderManagement').value).toBe(13_634_623.28);

    // The stale live reply arrives late and must be dropped.
    releaseLive(LIVE_ACCOUNTS);
    tick(50);

    expect(card('assetsUnderManagement').value).toBe(13_634_623.28);
    expect(component.accounts?.length).toBe(1);
    settle();
  }));

  it('clears the instant-dependent payloads while the snapshot is in flight', fakeAsync(() => {
    mount();
    expect(component.accounts).not.toBeNull();

    // Hold the snapshot reply open, so the in-flight state is observable at all:
    // with synchronous stubs it would already have landed inside onScrub.
    const held = new Subject<Account[]>();
    (ledger.listAccounts as jasmine.Spy).and.callFake((silent?: boolean, at?: Date | null) =>
      at ? held.asObservable() : of(LIVE_ACCOUNTS));

    component.onScrub(SCRUB_TO);

    // Before the reply lands: nothing describing the live instant may stay up,
    // least of all the accounts array the two browser-computed cards read.
    expect(component.accounts).toBeNull();
    expect(component.trends).toBeNull();
    expect(component.chartSeries).toBeNull();
    expect(component.entries).toBeNull();
    expect(component.trial).toBeNull();
    expect(card('assetsUnderManagement').value).toBeNull();

    held.next(SNAPSHOT_ACCOUNTS);
    held.complete();
    tick(50);
    expect(card('assetsUnderManagement').value).toBe(13_634_623.28);
    settle();
  }));

  it('puts the scrubber in the header and learns the ledger range from the payload', fakeAsync(() => {
    mount();
    const scrubber = fixture.debugElement.query(By.directive(TimelineScrubberComponent));
    expect(scrubber).withContext('the scrubber should be in the header').toBeTruthy();
    // historyStart rides on the chart-series payload; the scrubber cannot offer a
    // range until it has one.
    expect(component.historyStart).toEqual(new Date('2024-10-01T03:51:05.354590Z'));
    settle();
  }));

  /**
   * The charts are the third thing the scrubber has to move, and the one that
   * failed silently.
   *
   * The bucket axis is built from a day and rolls the payload's rows into it. It
   * was anchored on the real today, so a snapshot's rows — a year or two in the
   * past — fell outside their own axis, were dropped, and every bucket came back
   * zero. The charts then drew "No data" over a payload carrying a full year of
   * history, which is exactly the misleading blank the KPI fix was about.
   */
  it('anchors the chart axis on the snapshot day, not on today', fakeAsync(() => {
    mount();

    // The payload the backend returns for range=1y at 2025-05-18: 12 monthly
    // points ending at the snapshot, all of them in the past.
    const historical: ChartSeries = {
      ...SERIES,
      dailyVolume: [
        { date: '2024-10-15', count: 12 }, { date: '2024-11-15', count: 30 },
        { date: '2024-12-15', count: 44 }, { date: '2025-01-15', count: 51 },
        { date: '2025-02-15', count: 47 }, { date: '2025-03-15', count: 60 },
        { date: '2025-04-15', count: 55 }, { date: '2025-05-15', count: 39 }
      ],
      dailyFlow: [
        { date: '2024-10-15', in: 100, out: 40 }, { date: '2025-01-15', in: 300, out: 120 },
        { date: '2025-05-15', in: 220, out: 90 }
      ]
    };
    (ledger.getChartSeries as jasmine.Spy).and.callFake((r: string) => of({ ...historical, range: r }));

    component.onScrub(SCRUB_TO);
    tick(50);
    fixture.detectChanges();

    expect(component.chartSeries).not.toBeNull();
    // The volume chart is only built when the buckets actually carry values.
    expect(component.volumeChart)
      .withContext('the volume chart must draw the snapshot\'s history, not "No data"')
      .not.toBeNull();
    expect(component.cashFlowChart).not.toBeNull();

    // And the axis ends on the snapshot, not on today: the last bucket is the
    // month of 2025-05, so no bucket may be labelled with a later month.
    const labels: string[] = component.volumeChart.labels;
    expect(labels.length).toBeGreaterThan(0);
    const last = labels[labels.length - 1];
    expect(last).toContain('2025');
    expect(labels.some((l) => /2026/.test(l)))
      .withContext('no bucket may be labelled with a date after the snapshot')
      .toBe(false);
    settle();
  }));

  /**
   * The SLA card, which is the one KPI the server can report no measurement for.
   *
   * Its samples are counted in the ledger's memory, so every instant before the
   * process started has none — and `compliancePercentBetween` answers 0 for an
   * empty window. Reported as a value, that claimed a total outage across the
   * whole of history, complete with the red "missed target" dot the card draws
   * for a breach of its 95% floor. The server sends `current: null` for such a
   * window now; the card must read "No data" and claim nothing.
   */
  it('shows no measurement, and no breach, for a window with no samples', fakeAsync(() => {
    mount();

    // What the backend returns for the SLA at a historical cutoff: no value, no
    // baseline, and a gap at every one of its seven points.
    const slaNoSamples = {
      current: null, previous: null, deltaPercent: null,
      history: [null, null, null, null, null, null, null]
    } as unknown as KpiTrend;
    (ledger.kpiTrends as jasmine.Spy).and.callFake((r: string) =>
      of({ ...SNAPSHOT_TRENDS, dashboardSlaCompliance: slaNoSamples, range: r }));

    component.onScrub(SCRUB_TO);
    tick(50);
    fixture.detectChanges();

    const sla = card('dashboardSlaCompliance');
    expect(sla.value).toBeNull();
    expect(component.kpiHasTrend(sla)).toBe(false);
    expect(component.kpiDelta(sla)).toBe('');
    // A null value is not a judgement, so the card carries no threshold dot at
    // all — rather than a red one asserting a target was missed.
    expect(component.kpiTargetState(sla)).toBeNull();

    const el = cardElement('Dashboard SLA');
    expect(el).withContext('the SLA card should render').toBeTruthy();
    const valueEl = el!.querySelector('.kpi-value')!;
    expect(valueEl.classList.contains('na'))
      .withContext('the card must be in its No-data state, not showing 0.0%')
      .toBe(true);
    expect(valueEl.textContent).toContain('No data');
    expect(el!.querySelector('.trend-none')?.textContent).toContain('—');
    expect(el!.querySelector('.kpi-dot'))
      .withContext('no threshold dot may be drawn for an unmeasured window')
      .toBeNull();
    settle();
  }));

  /**
   * The other half of the rule, and the constraint on the fix: a window that
   * <em>does</em> hold samples must still report a real percentage. This is also
   * the state the live dashboard is normally in, since a freshly started service
   * has no samples in the 24 hours before it.
   */
  it('still reports a real SLA percentage for a window that has samples', fakeAsync(() => {
    mount();
    const slaMeasured = {
      current: 28.07, previous: 0, deltaPercent: null,
      history: [null, null, null, null, null, null, 28.07]
    } as unknown as KpiTrend;
    (ledger.kpiTrends as jasmine.Spy).and.callFake((r: string) =>
      of({ ...SNAPSHOT_TRENDS, dashboardSlaCompliance: slaMeasured, range: r }));

    component.refreshAll('range-change');
    tick(50);
    fixture.detectChanges();

    const sla = card('dashboardSlaCompliance');
    expect(sla.value).toBe(28.07);
    // No baseline to compare against, so the trend is a dash — but the value stands.
    expect(component.kpiHasTrend(sla)).toBe(false);
    expect(component.kpiTargetState(sla))
      .withContext('28.07% is under the 95% floor, so the dot reports a miss')
      .toBe('missed');
    settle();
  }));
});
