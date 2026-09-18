import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MessageService } from 'primeng/api';
import { catchError, throwError } from 'rxjs';
import { SILENT_REQUEST } from './http-context.tokens';

/**
 * Global HTTP error handling. Catches failed requests, maps status
 * codes to friendly messages, shows a toast, and re-throws the error.
 *
 * This interceptor is the single owner of error toasts: components must not
 * raise their own, otherwise one failure produces two messages. Requests
 * flagged SILENT_REQUEST skip the toast because their caller renders the
 * failure itself (see http-context.tokens.ts).
 *
 * Error-body convention: the backend explains its own failures as
 * `{ "error": "<message>" }` — see `ApiExceptionHandler` in ledger-service and
 * the inline handlers in its controllers. Those messages are more specific than
 * anything derivable from the status code alone (e.g. "TELLER role cannot
 * transfer more than $10,000. Manager approval required."), so they win over
 * the generic text. The exception is 401: an unauthenticated session is already
 * handled by the Keycloak login flow, so it keeps its generic wording.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const messages = inject(MessageService);
  return next(req).pipe(
    catchError((err: HttpErrorResponse) => {
      const status = err?.status ?? 0;
      let detail = 'An unexpected error occurred.';

      if (status === 0) {
        detail = 'Cannot reach the server. Please check your connection.';
      } else if (status === 400) {
        detail = 'Invalid request. Please check your inputs.';
      } else if (status === 401 || status === 403) {
        detail = 'You are not authorized.';
      } else if (status === 404) {
        detail = 'The requested resource was not found.';
      } else if (status === 408 || status === 504) {
        detail = 'The server took too long to respond.';
      } else if (status >= 500) {
        detail = 'Something went wrong on our side. Please try again in a moment.';
      }

      // `err.error` is the parsed body: `{ error: "..." }` from the backend
      // (never a string for a network failure, hence the type guard).
      const backendMessage =
        err?.error && typeof err.error === 'object' && typeof err.error.error === 'string'
          ? err.error.error.trim()
          : '';
      if (backendMessage && status !== 401) {
        detail = backendMessage;
      }

      if (!req.context.get(SILENT_REQUEST)) {
        messages.add({ severity: 'error', summary: 'Error', detail, life: 5000 });
      }
      return throwError(() => err);
    })
  );
};
