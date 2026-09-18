import { Component, Input, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';

/** One point of the rendered polyline, in viewBox units. */
interface PlotPoint {
  x: number;
  y: number;
}

/**
 * Inline mini chart for a KPI card (brief 5.3).
 *
 * Hand-drawn SVG rather than a chart library: at 72x28 a full Chart.js instance
 * costs far more than the shape it draws, and SVG strokes inherit the theme's
 * colour tokens (brief 7 — no hard-coded hex outside the token file). The stroke
 * colour comes from `currentColor`, so the host sets the tone with a token.
 *
 * The draw-on animation plays once, on first paint. The Command Center polls
 * every 5s, so replaying it every tick would be noise; later values update the
 * geometry in place, matching how the count-up directive treats KPI numbers.
 */
@Component({
  selector: 'app-kpi-sparkline',
  standalone: true,
  imports: [CommonModule],
  template: `
    @if (hasData) {
      <svg class="spark" [attr.width]="width" [attr.height]="height"
           [attr.viewBox]="'0 0 ' + viewBoxWidth + ' ' + viewBoxHeight"
           preserveAspectRatio="none" aria-hidden="true" focusable="false">
        <path class="spark-area" [attr.d]="areaPath"></path>
        <path class="spark-line" [class.animate]="playEntrance"
              [style.--spark-length]="pathLength" [attr.d]="linePath"></path>
      </svg>
    } @else {
      <span class="spark-empty" [style.width.px]="width" [style.height.px]="height">—</span>
    }
  `,
  styles: [`
    :host { display: inline-flex; align-items: center; }

    .spark { display: block; overflow: visible; }

    .spark-line {
      fill: none;
      stroke: currentColor;
      stroke-width: 1.5;
      stroke-linecap: round;
      stroke-linejoin: round;
      vector-effect: non-scaling-stroke;
    }

    /* Draw-on effect, first paint only (brief 6). */
    .spark-line.animate {
      stroke-dasharray: var(--spark-length);
      stroke-dashoffset: var(--spark-length);
      animation: spark-draw var(--duration-chart, 600ms) var(--ease-out) forwards;
    }
    @keyframes spark-draw {
      to { stroke-dashoffset: 0; }
    }

    .spark-area { fill: currentColor; opacity: 0.12; stroke: none; }

    .spark-empty {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: var(--text-tertiary);
      font-size: var(--text-sm);
    }

    @media (prefers-reduced-motion: reduce) {
      .spark-line.animate { animation: none; stroke-dashoffset: 0; }
    }
  `]
})
export class KpiSparklineComponent implements OnChanges {
  /** Daily values, oldest first. Fewer than two usable points renders a dash. */
  @Input() values: number[] | null = null;

  /** Rendered size in px; the viewBox is stretched to fit. */
  @Input() width = 72;
  @Input() height = 28;

  /** Internal coordinate space; keeps the maths independent of the CSS size. */
  readonly viewBoxWidth = 100;
  readonly viewBoxHeight = 30;

  linePath = '';
  areaPath = '';
  hasData = false;
  playEntrance = false;
  pathLength = 100;

  private hasDrawn = false;

  ngOnChanges(): void {
    const points = this.series();
    if (points.length < 2) {
      this.hasData = false;
      this.linePath = '';
      this.areaPath = '';
      return;
    }

    const plots = this.scale(points);
    this.linePath = this.smoothPath(plots);
    const last = plots[plots.length - 1];
    this.areaPath = `${this.linePath} L ${last.x} ${this.viewBoxHeight} L ${plots[0].x} ${this.viewBoxHeight} Z`;
    this.pathLength = this.roughLength(plots);
    this.playEntrance = !this.hasDrawn;
    this.hasData = true;
    this.hasDrawn = true;
  }

  /** Finite points only: a gap in history is dropped rather than plotted as 0. */
  private series(): number[] {
    return (this.values ?? []).map((v) => Number(v)).filter((v) => Number.isFinite(v));
  }

  /** Maps values onto the viewBox, with padding so the stroke is not clipped. */
  private scale(values: number[]): PlotPoint[] {
    const min = Math.min(...values);
    const max = Math.max(...values);
    const span = max - min;
    const pad = 3;
    const usable = this.viewBoxHeight - pad * 2;
    const stepX = this.viewBoxWidth / (values.length - 1);

    return values.map((value, i) => ({
      x: i * stepX,
      // A flat series has no span to scale by; centring it reads as "no change"
      // instead of drawing it along the floor of the box.
      y: span === 0 ? this.viewBoxHeight / 2 : pad + (1 - (value - min) / span) * usable
    }));
  }

  /**
   * Catmull-Rom smoothing expressed as cubic segments, so the sparkline reads as
   * a trend line rather than a jagged bar chart. Control points are clamped to
   * the plot area so smoothing cannot overshoot the box.
   */
  private smoothPath(points: PlotPoint[]): string {
    const clampY = (y: number) => Math.max(0, Math.min(this.viewBoxHeight, y));
    let d = `M ${points[0].x} ${points[0].y}`;

    for (let i = 0; i < points.length - 1; i++) {
      const p0 = points[i - 1] ?? points[i];
      const p1 = points[i];
      const p2 = points[i + 1];
      const p3 = points[i + 2] ?? p2;

      const c1x = p1.x + (p2.x - p0.x) / 6;
      const c1y = clampY(p1.y + (p2.y - p0.y) / 6);
      const c2x = p2.x - (p3.x - p1.x) / 6;
      const c2y = clampY(p2.y - (p3.y - p1.y) / 6);

      d += ` C ${c1x} ${c1y} ${c2x} ${c2y} ${p2.x} ${p2.y}`;
    }
    return d;
  }

  /**
   * Path length estimate for the dash animation. The exact figure would need a
   * DOM measurement (and `getTotalLength` is unavailable in unit tests), but a
   * generous over-estimate is harmless here: it only makes the stroke finish
   * drawing slightly before the animation ends.
   */
  private roughLength(points: PlotPoint[]): number {
    let length = 0;
    for (let i = 1; i < points.length; i++) {
      length += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y);
    }
    return Math.ceil(length) || 100;
  }
}
