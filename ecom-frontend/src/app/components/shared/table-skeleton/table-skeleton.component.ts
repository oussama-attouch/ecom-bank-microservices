import { Component, Input } from '@angular/core';
import { CommonModule } from '@angular/common';

/**
 * Table skeleton (brief 5.9).
 *
 * Renders `rows` placeholder rows of `cols` bars each, so a `p-table`'s first
 * load shows the shape of the data instead of a spinner. Same `.skeleton`
 * recipe as the dashboard's placeholders (brief 5.9), so both animate in step.
 *
 * Consumers render it *instead of* the table while the first response is in
 * flight — never on a refresh, and never inside `pTemplate="emptymessage"`
 * (that branch only shows for an empty value array).
 */
@Component({
  selector: 'app-table-skeleton',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="sk-table" role="status" aria-live="polite" aria-label="Loading data">
      @for (r of rowsArray; track r) {
        <div class="sk-row">
          @for (c of colsArray; track c) {
            <div class="skeleton sk-cell" [style.width]="barWidth(c)"></div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }

    .sk-table { display: flex; flex-direction: column; gap: var(--space-5); padding: var(--space-3) 0; }
    .sk-row { display: flex; align-items: center; gap: var(--space-5); }

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
    .sk-cell { height: 14px; flex: 0 0 auto; }
  `]
})
export class TableSkeletonComponent {
  /** Placeholder rows to draw. */
  @Input() rows = 5;
  /** Skeleton bars per row — pass the real table's column count. */
  @Input() cols = 5;

  /** Cycled bar widths, so a row reads as a table rather than as noise. */
  private static readonly WIDTHS = [15, 10, 20, 20, 10];

  get rowsArray(): number[] {
    return Array.from({ length: Math.max(0, this.rows) }, (_, i) => i);
  }

  get colsArray(): number[] {
    return Array.from({ length: Math.max(0, this.cols) }, (_, i) => i);
  }

  barWidth(col: number): string {
    return TableSkeletonComponent.WIDTHS[col % TableSkeletonComponent.WIDTHS.length] + '%';
  }
}
