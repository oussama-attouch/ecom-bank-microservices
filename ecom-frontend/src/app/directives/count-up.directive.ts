import { Directive, ElementRef, Input, OnChanges, OnDestroy, inject } from '@angular/core';

/**
 * Counts a numeric value up from zero the first time it renders, then writes
 * later values straight through.
 *
 * The Command Center polls every 5s (brief 6): the animation must play on the
 * first paint only, so every subsequent input change is rendered instantly.
 * The host element must not also bind its own text content.
 *
 * Usage: `<span [appCountUp]="total" countUpPrefix="$" [countUpDecimals]="2"></span>`
 */
@Directive({ selector: '[appCountUp]', standalone: true })
export class CountUpDirective implements OnChanges, OnDestroy {
  @Input('appCountUp') value: number | null = null;
  @Input() countUpPrefix = '';
  @Input() countUpSuffix = '';
  @Input() countUpDecimals = 0;

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private hasAnimated = false;
  private frame = 0;

  ngOnChanges(): void {
    const target = Number(this.value ?? 0);
    if (!Number.isFinite(target)) {
      this.render(0);
      return;
    }
    if (this.hasAnimated) {
      this.render(target);   // poll refresh: update in place, no re-animation
      return;
    }
    this.hasAnimated = true;
    this.animate(target);
  }

  ngOnDestroy(): void {
    cancelAnimationFrame(this.frame);
  }

  private animate(target: number): void {
    const duration = this.durationMs();
    if (duration <= 0 || target === 0) {
      this.render(target);
      return;
    }
    const start = performance.now();
    const step = (now: number) => {
      // A rAF timestamp can precede the performance.now() captured when the
      // frame was scheduled, which made the first frame slightly negative and
      // flashed e.g. "$-161.07". Clamp both ends.
      const t = Math.max(0, Math.min(1, (now - start) / duration));
      const eased = 1 - Math.pow(1 - t, 3);   // ease-out cubic
      this.render(target * eased);
      if (t < 1) {
        this.frame = requestAnimationFrame(step);
      } else {
        this.render(target);
      }
    };
    this.frame = requestAnimationFrame(step);
  }

  private render(v: number): void {
    const decimals = this.countUpDecimals;
    const value = decimals === 0 ? Math.round(v) : v;
    this.host.nativeElement.textContent =
      this.countUpPrefix +
      value.toLocaleString('en-US', {
        minimumFractionDigits: decimals,
        maximumFractionDigits: decimals
      }) +
      this.countUpSuffix;
  }

  /** Timing comes from the token set, so the animation stays consistent with the brief. */
  private durationMs(): number {
    const raw = getComputedStyle(document.documentElement).getPropertyValue('--duration-count').trim();
    const parsed = parseFloat(raw);
    if (!Number.isFinite(parsed)) return 800;
    return raw.endsWith('ms') ? parsed : parsed * 1000;
  }
}
