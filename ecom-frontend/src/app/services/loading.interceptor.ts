import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs';
import { LoadingService } from './loading.service';
import { SILENT_REQUEST } from './http-context.tokens';

/**
 * Drives the global loading overlay from in-flight requests.
 *
 * <p>A request flagged {@link SILENT_REQUEST} is left out of the counter
 * entirely. That flag exists for background polling — the Command Center
 * refreshes every five seconds, and counting those made the overlay appear and
 * disappear on every cycle, flashing over a page the user was reading. A poll is
 * not a user-initiated wait, so it should not claim to be one.
 *
 * <p>Silent requests still run, still fail, and are still reported: the caller
 * that set the flag owns the failure UI (the Command Center shows a "Partial
 * data" badge and raises one toast on the transition into a degraded state).
 *
 * <p>The counter is only skipped, never decremented, so a silent request cannot
 * unbalance it and hide a legitimate wait that is still in flight.
 */
export const loadingInterceptor: HttpInterceptorFn = (req, next) => {
  const loading = inject(LoadingService);

  if (req.context.get(SILENT_REQUEST)) {
    return next(req);
  }

  loading.start();
  return next(req).pipe(finalize(() => loading.stop()));
};
