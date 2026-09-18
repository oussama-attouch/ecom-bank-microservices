import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * KPI card skeleton (brief 5.9).
 *
 * Renders `count` placeholder cards, each mimicking a KPI card's shape (a short
 * label bar over a 34px value bar). The grid copies the dashboard's `.kpi-grid`
 * so a page that swaps its summary cards for this keeps its layout: 4 columns,
 * 2 under 1100px, 1 under 600px.
 */
@Component({
  selector: 'app-kpi-skeleton',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="kpi-grid" role="status" aria-live="polite" aria-label="Loading metrics">
      @for (i of items; track i) {
        <div class="kpi">
          <div class="skeleton sk-label"></div>
          <div class="skeleton sk-value"></div>
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }

    .kpi-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-5); margin-bottom: var(--space-5); }
    .kpi {
      background: var(--surface-0);
      border: 1px solid var(--border-subtle);
      border-radius: var(--radius-xl);
      padding: var(--space-5);
    }

    /* ---- Skeletons (brief 5.9) ---- */
    .skeleton {
      background: linear-gradient(90deg, var(--surface-2) 0%, var(--surface-1) 50%, var(--surface-2) 100%);
      background-size: 200% 100%;
      animation: sk 1.5s linear infinite;
      border-radius: var(--radius-sm);
    }
    @keyframes sk {
      from { background-position: 0% 0; }
      to   { background-position: 200% 0; }
    }
    .sk-label { height: 14px; width: 40%; }
    .sk-value { height: 34px; width: 60%; margin-top: var(--space-3); }

    @media (max-width: 1100px) { .kpi-grid { grid-template-columns: repeat(2, 1fr); } }
    @media (max-width: 600px) { .kpi-grid { grid-template-columns: 1fr; } }
  `]
})
export class KpiSkeletonComponent {
  /** Number of placeholder cards to draw. */
  @Input() count = 4;

  get items(): number[] {
    return Array.from({ length: Math.max(0, this.count) }, (_, i) => i);
  }
}
