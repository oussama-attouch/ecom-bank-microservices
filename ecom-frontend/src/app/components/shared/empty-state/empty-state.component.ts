import { Component, EventEmitter, Input, Output } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

/**
 * Shared empty state for tables and lists (brief 5.10).
 *
 * Centred icon in a muted circle, a short headline, an optional hint and an
 * optional CTA. The CTA either routes (`ctaRoute`) or emits (`ctaClick`), so a
 * page can wire it to an existing action without duplicating markup.
 */
@Component({
  selector: 'app-empty-state',
  standalone: true,
  imports: [CommonModule, RouterLink, ButtonModule],
  template: `
    <div class="empty">
      <span class="empty-icon"><i [class]="icon"></i></span>
      <p class="empty-title">{{ title }}</p>
      @if (hint) {
        <p class="empty-hint">{{ hint }}</p>
      }
      @if (ctaLabel && ctaRoute) {
        <p-button [label]="ctaLabel" severity="primary" [routerLink]="ctaRoute" styleClass="empty-cta"></p-button>
      } @else if (ctaLabel) {
        <p-button [label]="ctaLabel" severity="primary" (onClick)="ctaClick.emit()" styleClass="empty-cta"></p-button>
      }
    </div>
  `,
  styles: [`
    .empty {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      gap: var(--space-2);
      padding: var(--space-9) var(--space-4);
      text-align: center;
    }
    .empty-icon {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 48px;
      height: 48px;
      margin-bottom: var(--space-1);
      border-radius: var(--radius-full);
      background: var(--surface-2);
      color: var(--text-secondary);
    }
    .empty-icon i { font-size: 1.4rem; }
    .empty-title {
      margin: 0;
      font-size: 16px;
      font-weight: var(--weight-semibold);
      color: var(--text-primary);
    }
    .empty-hint {
      margin: 0;
      max-width: 42ch;
      font-size: var(--text-base);
      color: var(--text-secondary);
    }
    :host ::ng-deep .empty-cta { margin-top: var(--space-2); }
  `]
})
export class EmptyStateComponent {
  @Input() icon = 'pi pi-inbox';
  @Input() title = 'Nothing here yet';
  @Input() hint?: string;
  @Input() ctaLabel?: string;
  @Input() ctaRoute?: string;
  @Output() ctaClick = new EventEmitter<void>();
}
