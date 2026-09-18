import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable, map } from 'rxjs';

export type NotificationSeverity = 'success' | 'info' | 'warn' | 'error';

export interface AppNotification {
  id: string;
  title: string;
  message: string;
  severity: NotificationSeverity;
  timestamp: Date;
  read: boolean;
}

const MAX_KEPT = 50;

/**
 * In-memory notification centre. Nothing is persisted: entries live for the
 * browser session only, which is enough for surfacing saga compensation/failure
 * events raised while the user is working.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly items = new BehaviorSubject<AppNotification[]>([]);

  readonly notifications$: Observable<AppNotification[]> = this.items.asObservable();
  readonly unreadCount$: Observable<number> = this.items.pipe(
    map((list) => list.filter((n) => !n.read).length)
  );

  /** Prepend a notification (newest first) and cap the history. */
  push(notification: {
    title: string;
    message: string;
    severity?: NotificationSeverity;
    timestamp?: Date;
  }): void {
    const item: AppNotification = {
      id: this.newId(),
      title: notification.title,
      message: notification.message,
      severity: notification.severity ?? 'info',
      timestamp: notification.timestamp ?? new Date(),
      read: false
    };
    this.items.next([item, ...this.items.value].slice(0, MAX_KEPT));
  }

  markRead(id: string): void {
    this.items.next(this.items.value.map((n) => (n.id === id ? { ...n, read: true } : n)));
  }

  markAllRead(): void {
    this.items.next(this.items.value.map((n) => (n.read ? n : { ...n, read: true })));
  }

  clear(): void {
    this.items.next([]);
  }

  hasUnread(): boolean {
    return this.items.value.some((n) => !n.read);
  }

  private newId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto
      ? crypto.randomUUID()
      : `n-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }
}
