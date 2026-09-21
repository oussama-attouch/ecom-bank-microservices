import {
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  signal,
} from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../services/auth.service';

/** How long the error banner stays up before retiring itself. */
const ERROR_AUTO_DISMISS_MS = 8000;

/**
 * How long to wait for the redirect to Keycloak before assuming it never
 * started. `authorize()` sets `window.location`, so the document normally
 * unloads within milliseconds; this only fires when the navigation is blocked
 * or the authority is misconfigured, which would otherwise leave the CTA
 * spinning forever with no way back.
 */
const REDIRECT_WATCHDOG_MS = 15000;

/** Id of the trust notice the CTA is described by. */
const TRUST_NOTICE_ID = 'login-trust-notice';

/**
 * Keycloak reports OAuth failures as `error` codes (RFC 6749 section 4.1.2.1).
 * Mapped to product copy rather than echoed raw; anything unrecognised falls
 * back to `error_description`.
 */
const ERROR_COPY: Record<string, string> = {
  access_denied: 'Sign-in was cancelled or denied. Please try again.',
  invalid_request: 'The sign-in request was rejected. Please try again.',
  unauthorized_client:
    'This application is not allowed to sign in. Contact an administrator.',
  unsupported_response_type:
    'This application requested an unsupported sign-in method. Contact an administrator.',
  server_error: 'Keycloak ran into a problem. Please try again in a moment.',
  temporarily_unavailable:
    'Keycloak is temporarily unavailable. Please try again in a moment.',
  login_required: 'Your session ended. Please sign in again.',
  interaction_required:
    'Keycloak needs another step before it can sign you in.',
};

/** `error_description` is reflected into the DOM, so it is bounded. */
const MAX_DESCRIPTION_LENGTH = 160;

/**
 * Login hero (brief 5.7).
 *
 * A Keycloak redirect landing, not a credential form: authentication is
 * `AuthService.login()` -> `oidcSecurityService.authorize()`, which navigates
 * the whole document to Keycloak. There are therefore no fields to validate
 * and no password to reveal — the only interactive element on the page is the
 * CTA, and the only state it owns is "started the redirect" plus whatever
 * failure Keycloak handed back.
 *
 * The shell hides its sidebar and topbar on this route (see `.auth-layout` in
 * styles.scss), so the component owns the full viewport.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [ButtonModule],
  templateUrl: './login.component.html',
  styleUrl: './login.component.scss',
})
export class LoginComponent {
  private readonly auth = inject(AuthService);
  private readonly messages = inject(MessageService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  /** True from the click until the browser leaves for Keycloak. */
  readonly submitting = signal(false);

  /** Keycloak's failure text, or a local one when the redirect never starts. */
  readonly errorMessage = signal<string | null>(null);

  readonly ctaLabel = computed(() =>
    this.submitting() ? 'Redirecting to Keycloak...' : 'Sign in with Keycloak'
  );

  private dismissTimer: ReturnType<typeof setTimeout> | null = null;
  private redirectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.readRedirectError();

    // PrimeNG renders <p-button> as a wrapper around a native <button>, and
    // `aria-describedby` is not one of its inputs, so the notice is wired to
    // the real control once the view exists.
    afterNextRender(() => this.describeCta());

    this.destroyRef.onDestroy(() => {
      this.clearDismissTimer();
      this.clearRedirectTimer();
    });
  }

  login(): void {
    if (this.submitting()) {
      return;
    }

    this.clearError();
    this.submitting.set(true);

    try {
      this.auth.login();
    } catch {
      this.fail('We could not start the sign-in redirect. Please try again.');
      return;
    }

    // Guards the case where authorize() returns without navigating: without
    // this the CTA would stay disabled and spinning indefinitely.
    this.redirectTimer = setTimeout(
      () => this.fail('The sign-in redirect did not start. Please try again.'),
      REDIRECT_WATCHDOG_MS
    );
  }

  /** Clears the banner and hands focus to the CTA, which outlives the ✕. */
  dismissError(): void {
    this.clearError();
    this.ctaButton()?.focus();
  }

  private fail(detail: string): void {
    this.clearRedirectTimer();
    this.submitting.set(false);
    this.setError(detail);
    this.messages.add({
      severity: 'error',
      summary: 'Sign-in failed',
      detail,
      life: 5000,
    });
  }

  private setError(message: string): void {
    this.clearDismissTimer();
    this.errorMessage.set(message);
    this.dismissTimer = setTimeout(() => {
      this.dismissTimer = null;
      this.errorMessage.set(null);
    }, ERROR_AUTO_DISMISS_MS);
  }

  private clearError(): void {
    this.clearDismissTimer();
    this.errorMessage.set(null);
  }

  /**
   * Reads a failure Keycloak appended to the URL. In the current wiring the
   * redirect target is /callback, so in practice this only fires if the app is
   * ever sent back to /login with an error; it is defensive, not decorative.
   */
  private readRedirectError(): void {
    const params = new URLSearchParams(window.location.search);
    const code = params.get('error');

    if (!code) {
      return;
    }

    this.setError(this.humanizeError(code, params.get('error_description')));
  }

  private humanizeError(code: string, description: string | null): string {
    const known = ERROR_COPY[code];

    if (known) {
      return known;
    }

    if (description) {
      return description.slice(0, MAX_DESCRIPTION_LENGTH);
    }

    return `Sign-in failed (${code}). Please try again.`;
  }

  private describeCta(): void {
    this.ctaButton()?.setAttribute('aria-describedby', TRUST_NOTICE_ID);
  }

  /**
   * The CTA inside PrimeNG's button. Scoped to `p-button` because the error
   * banner's dismiss control is a plain sibling <button> and precedes it in
   * DOM order.
   */
  private ctaButton(): HTMLButtonElement | null {
    return this.host.nativeElement.querySelector<HTMLButtonElement>(
      'p-button button'
    );
  }

  private clearDismissTimer(): void {
    if (this.dismissTimer !== null) {
      clearTimeout(this.dismissTimer);
      this.dismissTimer = null;
    }
  }

  private clearRedirectTimer(): void {
    if (this.redirectTimer !== null) {
      clearTimeout(this.redirectTimer);
      this.redirectTimer = null;
    }
  }
}
