import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { MessageService } from 'primeng/api';
import { DashboardComponent } from './dashboard.component';
import { LedgerService } from '../../services/ledger.service';
import { JournalService } from '../../services/journal.service';
import { of } from 'rxjs';

/**
 * The x axis of the volume and cash-flow charts.
 *
 * Both are drawn on a Chart.js *category* scale, and a category tick carries
 * the point's index as its `value`: `CategoryScale.buildTicks` emits
 * `{ value: i }` and the scale's own default callback maps that index through
 * `getLabelForValue`. The dashboard supplies its own callback for tick thinning
 * and used to return `value` untouched, so the axis read 0, 1, 2 … instead of
 * the dates the chart-series payload carries. The label has to be asked of the
 * scale — which is why `this` matters here, since Chart.js invokes the callback
 * with the scale bound.
 */
describe('Command Center chart x-axis labels', () => {
  let component: DashboardComponent;

  beforeEach(async () => {
    localStorage.clear();
    await TestBed.configureTestingModule({
      imports: [DashboardComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), MessageService]
    }).compileComponents();

    component = TestBed.createComponent(DashboardComponent).componentInstance;
    const ledger = TestBed.inject(LedgerService);
    const journal = TestBed.inject(JournalService);

    // Stubbed so the axis can be inspected without the poll reaching a network.
    spyOn(ledger, 'listAccounts').and.returnValue(of([]));
    spyOn(ledger, 'listSagas').and.returnValue(of([]));
    spyOn(ledger, 'kpiTrends').and.returnValue(of(null as any));
    spyOn(ledger, 'getChartSeries').and.returnValue(of(null as any));
    spyOn(journal, 'entries').and.returnValue(of([]));
    spyOn(journal, 'trialBalance').and.returnValue(of({ totalDebits: 0, totalCredits: 0, balanced: true }));
  });

  afterEach(() => {
    component.ngOnDestroy();
    localStorage.clear();
  });

  /** A category scale as Chart.js presents itself to a tick callback. */
  const categoryScale = (labels: string[]) => ({
    getLabelForValue: (value: string | number) => labels[Number(value)] ?? value
  });

  /** The callback the volume, cash-flow and saga charts are drawn with. */
  const tickCallback = () => component.barOptions.scales.x.ticks.callback;

  it('prints the date a category tick stands for, not its index', () => {
    localStorage.setItem('dashboard.range', '7D');
    component.ngOnInit();

    const callback = tickCallback();
    // 7D labels every day, so the thinning is not what is under test here.
    expect(callback.call(categoryScale(['Sep 11', 'Sep 12', 'Sep 13']), 0, 0)).toBe('Sep 11');
    expect(callback.call(categoryScale(['Sep 11', 'Sep 12', 'Sep 13']), 2, 2)).toBe('Sep 13');
  });

  it('thins the crowded ranges without falling back to indices', () => {
    localStorage.setItem('dashboard.range', '30D');
    component.ngOnInit();

    const scale = categoryScale(Array.from({ length: 30 }, (_, i) => `Aug ${i + 1}`));
    const callback = tickCallback();

    expect(callback.call(scale, 0, 0)).toBe('Aug 1');
    expect(callback.call(scale, 1, 1)).toBeNull();   // nothing on the days between
    expect(callback.call(scale, 3, 3)).toBe('Aug 4');
  });

  it('falls back to the raw value when the scale cannot resolve a label', () => {
    localStorage.setItem('dashboard.range', '7D');
    component.ngOnInit();

    expect(tickCallback().call({}, 'n/a', 0)).toBe('n/a');
  });

  it('rebuilds the axis when the range changes, so the stride follows the range', () => {
    localStorage.setItem('dashboard.range', '7D');
    component.ngOnInit();
    const before = tickCallback();

    component.range = '90D';
    component.onRangeChange();

    // A stride baked into the old options object would keep labelling every
    // point of a 13-week axis.
    expect(tickCallback()).not.toBe(before);
    const scale = categoryScale(Array.from({ length: 13 }, (_, i) => `Jun ${i * 7 + 1}`));
    const weekly = tickCallback();
    expect(weekly.call(scale, 0, 0)).toBe('Jun 1');
    expect(weekly.call(scale, 1, 1)).toBeNull();
    expect(weekly.call(scale, 2, 2)).toBe('Jun 15');
  });
});
