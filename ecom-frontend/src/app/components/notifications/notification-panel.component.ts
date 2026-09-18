import { Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { BadgeModule } from 'primeng/badge';
import { OverlayPanelModule } from 'primeng/overlaypanel';
import { TagModule } from 'primeng/tag';
import { NotificationService, AppNotification, NotificationSeverity } from '../../services/notification.service';

@Component({
  selector: 'app-notification-panel',
  standalone: true,
  imports: [CommonModule, ButtonModule, BadgeModule, OverlayPanelModule, TagModule],
  template: `
    <div class="bell-wrap">
      <button pButton icon="pi pi-bell" class="p-button-text p-button-rounded"
              aria-label="Notifications" (click)="panel.toggle($event)"></button>
      @if (unread$ | async; as unread) {
        @if (unread > 0) {
          <p-badge [value]="unread > 9 ? '9+' : unread.toString()" severity="danger" class="bell-badge"></p-badge>
        }
      }
    </div>

    <p-overlayPanel #panel [style]="{ width: '380px' }">
      <div class="head">
        <strong>Notifications</strong>
        <div class="actions">
          <p-button label="Mark all read" size="small" severity="secondary" [text]="true"
                    [disabled]="(unread$ | async) === 0" (onClick)="markAllRead()"></p-button>
          <p-button label="Clear" size="small" severity="secondary" [text]="true"
                    [disabled]="(notifications$ | async)?.length === 0" (onClick)="clear()"></p-button>
        </div>
      </div>

      <div class="list">
        @if ((notifications$ | async)?.length) {
          @for (n of notifications$ | async; track n.id) {
            <div class="item" [class.unread]="!n.read" (click)="markRead(n)">
              <div class="item-top">
                <p-tag [value]="n.severity.toUpperCase()" [severity]="tagSeverity(n.severity)"></p-tag>
                <span class="when">{{ n.timestamp | date:'HH:mm:ss' }}</span>
              </div>
              <div class="title">{{ n.title }}</div>
              <div class="msg">{{ n.message }}</div>
            </div>
          }
        } @else {
          <div class="empty">
            <i class="pi pi-inbox"></i>
            <span>No notifications</span>
          </div>
        }
      </div>
    </p-overlayPanel>
  `,
  styles: [`
    .bell-wrap { position: relative; display: inline-flex; }
    .bell-badge { position: absolute; top: -2px; right: -2px; pointer-events: none; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px;
            border-bottom: 1px solid var(--border); padding-bottom: 8px; margin-bottom: 8px; }
    .actions { display: flex; gap: 4px; }
    .list { max-height: 360px; overflow-y: auto; }
    .item { padding: 10px; border-radius: 8px; border: 1px solid var(--border); margin-bottom: 8px; cursor: pointer; }
    .item.unread { border-left: 4px solid var(--accent); background: rgba(37, 99, 235, 0.06); }
    .item-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
    .when { font-size: .72rem; color: var(--text-muted); font-family: ui-monospace, monospace; }
    .title { font-weight: 600; font-size: .9rem; }
    .msg { font-size: .8rem; color: var(--text-muted); word-break: break-word; }
    .empty { display: flex; flex-direction: column; align-items: center; gap: 8px; padding: 2rem; color: var(--text-muted); }
    .empty i { font-size: 1.6rem; opacity: .6; }
  `]
})
export class NotificationPanelComponent {
  private readonly notifications = inject(NotificationService);

  readonly notifications$ = this.notifications.notifications$;
  readonly unread$ = this.notifications.unreadCount$;

  markRead(n: AppNotification): void {
    if (!n.read) this.notifications.markRead(n.id);
  }

  markAllRead(): void {
    this.notifications.markAllRead();
  }

  clear(): void {
    this.notifications.clear();
  }

  tagSeverity(s: NotificationSeverity): 'success' | 'info' | 'warn' | 'danger' {
    switch (s) {
      case 'success': return 'success';
      case 'warn': return 'warn';
      case 'error': return 'danger';
      default: return 'info';
    }
  }
}
