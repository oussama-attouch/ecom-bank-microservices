import { HttpContext, HttpContextToken } from '@angular/common/http';

/**
 * Marks a request whose failure is already reported by the caller's own UI.
 *
 * The global error interceptor normally raises a toast for every failed
 * request, which is right for user-initiated actions but wrong for background
 * polling: the Command Center refreshes every 5 seconds, so an outage would
 * queue a new toast every 5 seconds. Flagging those requests SILENT keeps the
 * interceptor out of the way, and the Command Center announces the outage once
 * (via its own toast and a persistent "Partial data" badge) instead.
 */
export const SILENT_REQUEST = new HttpContextToken<boolean>(() => false);

/** Request options helper: sets SILENT_REQUEST when `silent` is true. */
export function silentWhen(silent: boolean): { context?: HttpContext } {
  return silent ? { context: new HttpContext().set(SILENT_REQUEST, true) } : {};
}
