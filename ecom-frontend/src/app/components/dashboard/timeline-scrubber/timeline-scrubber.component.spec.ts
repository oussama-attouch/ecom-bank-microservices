import { TestBed, fakeAsync, tick } from '@angular/core/testing';
import { TimelineScrubberComponent } from './timeline-scrubber.component';

/**
 * The scrubber's contract with the dashboard.
 *
 * Worth pinning because three of its four rules are invisible in the template:
 * that a drag is debounced, that a drag ending where it started emits nothing,
 * that "Return to Live" is *not* debounced, and that an unknown range leaves the
 * control unusable rather than pointing at an instant nobody measured. Each of
 * those is a rule the dashboard relies on to avoid firing six endpoint reads it
 * did not need, and each is silent when it regresses — the dashboard would just
 * get slower, or would refetch an instant it is already showing.
 */
describe('TimelineScrubberComponent', () => {
  /** Two years, to match the seeded ledger's span. */
  const EARLIEST = new Date('2024-10-01T04:51:05Z');
  const LATEST = new Date('2026-09-24T12:00:00Z');

  /** The debounce the component documents, in ms. */
  const DEBOUNCE = 300;

  function build(earliest: Date | null = EARLIEST, latest: Date | null = LATEST) {
    const fixture = TestBed.createComponent(TimelineScrubberComponent);
    const component = fixture.componentInstance;
    const emitted: (Date | null)[] = [];
    component.scrub.subscribe((value) => emitted.push(value));
    component.earliest = earliest;
    component.latest = latest;
    component.ngOnInit();
    return { component, emitted };
  }

  afterEach(() => TestBed.resetTestingModule());

  it('maps the range to whole minutes, which is the precision it displays', () => {
    const { component } = build();

    // Two years all but a week or so: ~51,000 steps of one minute each.
    expect(component.steps).toBeGreaterThan(50000);
    expect(component.selectedMs).toBe(LATEST.getTime());
    expect(component.live).toBe(true);
  });

  it('debounces a drag, emitting only where it settles', fakeAsync(() => {
    const { component, emitted } = build();

    // Three positions in quick succession, as a drag produces.
    component.sliderValue = 10;
    tick(100);
    component.sliderValue = 20;
    tick(100);
    component.sliderValue = 30;

    expect(emitted).toEqual([]);          // nothing while the drag is in flight
    tick(DEBOUNCE);

    expect(emitted.length).toBe(1);       // one read for the whole drag, not three
    expect(emitted[0]!.getTime()).toBe(EARLIEST.getTime() + 30 * 60_000);
    expect(component.live).toBe(false);
  }));

  it('does not re-emit an instant the dashboard is already showing', fakeAsync(() => {
    const { component, emitted } = build();

    component.sliderValue = 42;
    tick(DEBOUNCE);
    expect(emitted.length).toBe(1);

    // A drag that wanders and comes back: same instant, so nothing to refetch.
    component.sliderValue = 7;
    component.sliderValue = 42;
    tick(DEBOUNCE);
    expect(emitted.length).toBe(1);
  }));

  it('emits return-to-live immediately rather than through the debounce', fakeAsync(() => {
    const { component, emitted } = build();

    component.sliderValue = 100;
    tick(DEBOUNCE);
    expect(emitted.length).toBe(1);

    component.returnToLive();

    // No tick: the button is a discrete action, and a 300ms wait to leave
    // snapshot mode is a pause the operator reads as the button not working.
    expect(emitted.length).toBe(2);
    expect(emitted[1]).toBeNull();
    expect(component.live).toBe(true);
    expect(component.selectedMs).toBe(LATEST.getTime());
  }));

  it('clamps an instant outside the ledger range instead of running off the end', () => {
    const { component } = build();

    component.value = new Date('1990-01-01T00:00:00Z');   // long before the ledger
    expect(component.selectedMs).toBe(EARLIEST.getTime());

    component.value = new Date('2099-01-01T00:00:00Z');   // long after "now"
    expect(component.selectedMs).toBe(LATEST.getTime());
  });

  it('is unusable until the ledger range is known', () => {
    const unknown = build(null, LATEST);
    expect(unknown.component.ready).toBe(false);
    expect(unknown.component.steps).toBe(0);

    // A degenerate range is also unusable: an empty log has nothing to scrub.
    const empty = build(LATEST, LATEST);
    expect(empty.component.ready).toBe(false);
  });

  it('mirrors an outside change of the viewing instant', () => {
    const { component } = build();

    component.sliderValue = 5;
    expect(component.live).toBe(false);

    // The banner's own Return to Live, arriving as an input binding.
    component.value = null;
    expect(component.live).toBe(true);
    expect(component.selectedMs).toBe(LATEST.getTime());
  });

  /**
   * The output carries Dates, and only Dates.
   *
   * Part of the regression that shipped as two bugs: the output used to be named
   * `change`, which is also a native DOM event, so the inner range input's own
   * `change` bubbled to this component's host and Angular delivered it to the
   * same binding as the output. The dashboard then received a DOM `Event` where
   * it expected a `Date`.
   *
   * The binding side of that is pinned in the dashboard's own spec, where the
   * collision actually happened; what this pins is that the component never
   * publishes anything but an instant, so a re-named binding cannot reintroduce
   * it silently.
   */
  it('publishes only Dates, never the native events the inner input bubbles', fakeAsync(() => {
    const fixture = TestBed.createComponent(TimelineScrubberComponent);
    const component = fixture.componentInstance;
    component.earliest = EARLIEST;
    component.latest = LATEST;
    const emitted: unknown[] = [];
    component.scrub.subscribe((value) => emitted.push(value));
    fixture.detectChanges();

    const input = fixture.nativeElement.querySelector('#ledger-scrubber') as HTMLInputElement;
    expect(input).withContext('the slider should render').not.toBeNull();

    // Exactly what a drag or an arrow-key adjustment produces in a real browser.
    input.value = '10';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
    fixture.detectChanges();

    // Still debouncing, and the native `change` above did not leak past it.
    expect(emitted).toEqual([]);

    tick(DEBOUNCE + 50);
    expect(emitted.length).toBe(1);
    expect(emitted[0] instanceof Date).toBe(true);
  }));
});
