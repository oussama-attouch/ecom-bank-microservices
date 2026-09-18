import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { AuthService } from '../../services/auth.service';

/**
 * Login hero (brief 5.7).
 *
 * The shell hides its sidebar and topbar on this route (see `.auth-layout` in
 * styles.scss), so the component owns the full viewport. Authentication itself
 * is a Keycloak redirect — there is nothing to submit here.
 */
@Component({
  selector: 'app-login',
  standalone: true,
  imports: [CommonModule, ButtonModule],
  template: `
    <div class="login-container">
      <span class="blob blob-indigo" aria-hidden="true"></span>
      <span class="blob blob-cyan" aria-hidden="true"></span>

      <div class="login-card">
        <span class="hero-logo">EC</span>
        <h1 class="title">E-Com Bank</h1>
        <p class="subtitle">Banking Platform Authentication</p>
        <p-button
          label="Sign in with Keycloak"
          icon="pi pi-sign-in"
          styleClass="hero-cta"
          (onClick)="login()"
        ></p-button>
      </div>
    </div>
  `,
  styles: [
    `
      .login-container {
        position: relative;
        display: flex;
        align-items: center;
        justify-content: center;
        min-height: 100vh;
        overflow: hidden;
        background: var(--gradient-hero);
      }

      /* Soft light blobs. Fixed + pointer-events:none so they never intercept
         the CTA underneath them. */
      .blob {
        position: fixed;
        pointer-events: none;
        filter: blur(100px);
        border-radius: var(--radius-full);
      }
      .blob-indigo {
        top: -160px;
        right: -160px;
        width: 600px;
        height: 600px;
        opacity: 0.5;
        background: var(--hero-blob-indigo);
      }
      .blob-cyan {
        bottom: -140px;
        left: -140px;
        width: 500px;
        height: 500px;
        opacity: 0.4;
        background: var(--hero-blob-cyan);
      }

      .login-card {
        position: relative;
        z-index: 1;
        width: 420px;
        max-width: calc(100vw - 32px);
        padding: 48px 40px;
        text-align: center;
        border: 1px solid var(--glass-on-dark-border);
        border-radius: 20px;
        background: var(--glass-on-dark);
        backdrop-filter: blur(20px);
        -webkit-backdrop-filter: blur(20px);
        box-shadow: var(--shadow-hero);
      }

      .hero-logo {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        width: 48px;
        height: 48px;
        border-radius: var(--radius-lg);
        background: var(--gradient-brand);
        color: var(--on-dark);
        font-size: 16px;
        font-weight: var(--weight-bold);
      }

      .title {
        margin: var(--space-4) 0 var(--space-2);
        font-size: var(--text-2xl);
        font-weight: var(--weight-semibold);
        letter-spacing: var(--tracking-tight);
        color: var(--on-dark);
      }

      .subtitle {
        margin: 0 0 var(--space-7);
        font-size: var(--text-md);
        color: var(--on-dark-muted);
      }

      :host ::ng-deep .hero-cta.p-button {
        width: 100%;
        height: 44px;
        border: 0;
        border-radius: var(--radius-lg);
        background: var(--gradient-brand);
        color: var(--on-dark);
        font-weight: var(--weight-semibold);
        transition: transform var(--duration-base) var(--ease-out),
                    filter var(--duration-base) var(--ease-out);
      }
      :host ::ng-deep .hero-cta.p-button:hover {
        transform: translateY(-1px);
        filter: brightness(1.08);
        background: var(--gradient-brand);
      }
      :host ::ng-deep .hero-cta.p-button:active {
        transform: translateY(0);
        background: var(--gradient-brand);
      }
    `
  ]
})
export class LoginComponent {
  constructor(private authService: AuthService) {}

  login(): void {
    this.authService.login();
  }
}
