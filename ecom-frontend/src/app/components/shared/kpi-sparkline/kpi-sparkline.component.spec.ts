import { ComponentFixture, TestBed } from '@angular/core/testing';
import { KpiSparklineComponent } from './kpi-sparkline.component';

/**
 * The sparkline's job is to turn a series into a drawable path without ever
 * producing geometry a browser refuses to render — a NaN in an SVG path is a
 * silently blank chart, which is the failure mode worth guarding.
 */
describe('KpiSparklineComponent', () => {
  let fixture: ComponentFixture<KpiSparklineComponent>;
  let component: KpiSparklineComponent;

  beforeEach(async () => {
    await TestBed.configureTestingModule({ imports: [KpiSparklineComponent] }).compileComponents();
    fixture = TestBed.createComponent(KpiSparklineComponent);
    component = fixture.componentInstance;
  });

  /**
   * Renders with the given series and returns the component.
   *
   * setInput rather than a plain assignment: only a bound input runs the
   * component's ngOnChanges, which is what builds the path.
   */
  function render(values: number[] | null): KpiSparklineComponent {
    fixture.componentRef.setInput('values', values);
    fixture.detectChanges();
    return component;
  }

  const numbers = (path: string): number[] => path.match(/-?\d+(\.\d+)?/g)?.map(Number) ?? [];

  it('draws one cubic segment per gap between points', () => {
    const sparkline = render([0, 1, 2, 3, 4, 5, 6]);

    expect(sparkline.hasData).toBe(true);
    expect(sparkline.linePath.startsWith('M ')).toBe(true);
    // Seven points, so six segments; the assertions below count the joins.
    expect(sparkline.linePath.split('C').length).toBe(7);
  });

  it('stays inside the viewBox, with padding at both ends', () => {
    const sparkline = render([0, 10, 5, 40, 20, 30, 1]);
    const ys = numbers(sparkline.linePath).filter((_, i) => i % 2 === 1); // y coordinates
    const xs = numbers(sparkline.linePath).filter((_, i) => i % 2 === 0); // x coordinates

    expect(Math.min(...ys)).toBeGreaterThanOrEqual(0);
    expect(Math.max(...ys)).toBeLessThanOrEqual(sparkline.viewBoxHeight);
    expect(Math.min(...xs)).toBe(0);
    expect(Math.max(...xs)).toBe(sparkline.viewBoxWidth);
  });

  it('centres a flat series instead of drawing it along the floor', () => {
    const sparkline = render([5, 5, 5, 5, 5, 5, 5]);
    const ys = numbers(sparkline.linePath).filter((_, i) => i % 2 === 1);

    expect(ys.every((y) => y === sparkline.viewBoxHeight / 2)).toBe(true);
  });

  it('closes the area path along the baseline', () => {
    const sparkline = render([1, 2, 3, 4, 5, 6, 7]);

    expect(sparkline.areaPath).toContain(sparkline.linePath);
    expect(sparkline.areaPath.endsWith(`L ${sparkline.viewBoxWidth} ${sparkline.viewBoxHeight} L 0 ${sparkline.viewBoxHeight} Z`))
      .toBe(true);
  });

  it('never emits NaN, even for a single repeated value', () => {
    const sparkline = render([0, 0]);

    expect(sparkline.linePath).not.toContain('NaN');
    expect(sparkline.areaPath).not.toContain('NaN');
  });

  it('renders a dash rather than a line when there is nothing to plot', () => {
    expect(render(null).hasData).toBe(false);
    expect(render([]).hasData).toBe(false);
    expect(render([42]).hasData).toBe(false); // one point is not a trend
  });

  it('drops non-finite points instead of plotting them as zero', () => {
    const sparkline = render([1, NaN, 3, 4] as number[]);

    expect(sparkline.hasData).toBe(true);
    expect(sparkline.linePath).not.toContain('NaN');
    expect(sparkline.linePath.split('C').length).toBe(3); // three surviving points
  });

  it('animates on the first paint and not on the polling refresh', () => {
    expect(render([1, 2, 3, 4, 5, 6, 7]).playEntrance).toBe(true);

    fixture.componentRef.setInput('values', [2, 3, 4, 5, 6, 7, 8]);
    fixture.detectChanges();
    expect(component.playEntrance).toBe(false);
  });

  it('estimates a positive dash length for the draw-on animation', () => {
    const sparkline = render([0, 100, 0, 100, 0, 100, 0]);

    expect(sparkline.pathLength).toBeGreaterThan(0);
    expect(Number.isFinite(sparkline.pathLength)).toBe(true);
  });
});
