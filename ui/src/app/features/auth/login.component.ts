import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { PasskeyService } from '../../core/auth/passkey.service';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';

@Component({
  selector: 'mc-login',
  standalone: true,
  imports: [FormsModule, RouterLink, GridCellNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-login-page" data-mc-nav-container>
      <div class="mc-card mc-login-card">
        <div class="mc-login-brand">
          <img
            src="assets/brand/moneycraft-logo-primary.svg"
            alt="MoneyCraft"
            width="210"
            height="38"
          />
          <span class="mc-badge mc-badge-slate">v1.0.0</span>
        </div>

        <h1>Sign In to MoneyCraft</h1>
        <p class="mc-login-subtitle">
          Authenticate with local Argon2id credentials, FIDO2 Passkey, or Enterprise OIDC.
        </p>

        <form (ngSubmit)="signInWithPassword()" class="mc-login-form">
          <div>
            <label class="mc-field-label" for="mc-login-username">Username</label>
            <input
              id="mc-login-username"
              type="text"
              name="username"
              class="mc-input"
              mcGridCellNav
              autocomplete="username webauthn"
              [(ngModel)]="username"
              required
            />
          </div>

          <div>
            <label class="mc-field-label" for="mc-login-password">Password</label>
            <input
              id="mc-login-password"
              type="password"
              name="password"
              class="mc-input"
              autocomplete="current-password"
              [(ngModel)]="password"
              required
            />
          </div>

          @if (errorMessage(); as err) {
            <div class="mc-field-error" role="alert">{{ err }}</div>
          }

          <button
            type="submit"
            class="mc-btn mc-btn-primary mc-login-submit"
            [disabled]="authStore.loading()"
          >
            Sign In (<span class="mc-key-ctrl">A</span> / Enter)
          </button>
        </form>

        <div class="mc-alt-auth-divider"><span>Passwordless &amp; Federated</span></div>

        <div class="mc-alt-auth-buttons">
          <button
            type="button"
            class="mc-btn"
            [disabled]="!passkeyService.isSupported() || authStore.loading()"
            (click)="signInWithPasskey()"
          >
            Sign In with Passkey (FIDO2)
          </button>

          <div class="mc-oidc-row">
            <input
              type="text"
              class="mc-input"
              [(ngModel)]="oidcIssuerId"
              name="oidcIssuerId"
              placeholder="OIDC Issuer ID (e.g. corporate-sso)"
              aria-label="Enterprise OIDC Issuer ID"
            />
            <button type="button" class="mc-btn" (click)="signInWithOidc()">
              OIDC SSO
            </button>
          </div>
        </div>

        <div class="mc-login-footer">
          <a routerLink="/setup" class="mc-setup-link">First-Run Setup Ceremony</a>
          <span>Raster Images Pvt. Ltd.</span>
        </div>
      </div>
    </div>
  `,
  styles: `
    .mc-login-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--mc-space-4);
      background: var(--mc-color-canvas);
    }

    .mc-login-card {
      width: min(440px, 94vw);
      padding: var(--mc-space-6);
    }

    .mc-login-brand {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: var(--mc-space-4);
    }

    .mc-login-subtitle {
      color: var(--mc-color-slate-600);
      margin: var(--mc-space-2) 0 var(--mc-space-4);
      font-size: var(--mc-font-size-sm);
    }

    .mc-login-form {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-login-submit {
      width: 100%;
      min-height: 36px;
      margin-top: var(--mc-space-1);
    }

    .mc-alt-auth-divider {
      margin: var(--mc-space-4) 0 var(--mc-space-3);
      text-align: center;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      font-weight: 600;
      border-bottom: var(--mc-border-subtle);
      line-height: 0.1em;

      span {
        background: var(--mc-color-surface);
        padding: 0 0.6rem;
      }
    }

    .mc-alt-auth-buttons {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-oidc-row {
      display: grid;
      grid-template-columns: 1fr auto;
      gap: var(--mc-space-2);
    }

    .mc-login-footer {
      margin-top: var(--mc-space-5);
      padding-top: var(--mc-space-3);
      border-top: var(--mc-border-subtle);
      display: flex;
      justify-content: space-between;
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
    }

    .mc-setup-link {
      color: var(--mc-color-teal-700);
      font-weight: 600;
      text-decoration: none;
    }
  `,
})
export class LoginComponent implements OnInit {
  readonly authStore = inject(AuthStore);
  readonly passkeyService = inject(PasskeyService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  username = '';
  password = '';
  oidcIssuerId = '';
  readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    const dispose = this.shortcutRegistry.bindHandler('screen.accept', 'SCREEN', () => {
      void this.signInWithPassword();
    });
    this.destroyRef.onDestroy(dispose);
  }

  async signInWithPassword(): Promise<void> {
    if (!this.username.trim() || !this.password) {
      this.errorMessage.set('Enter both username and password.');
      return;
    }
    this.errorMessage.set(null);
    try {
      await this.authStore.loginWithPassword({
        username: this.username.trim(),
        password: this.password,
      });
      await this.router.navigateByUrl('/app/gateway');
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Invalid username or password.');
    }
  }

  async signInWithPasskey(): Promise<void> {
    this.errorMessage.set(null);
    try {
      const principal = await this.passkeyService.authenticateWithPasskey(
        this.username.trim() || undefined,
      );
      this.authStore.setAuthenticatedPrincipal(principal);
      await this.router.navigateByUrl('/app/gateway');
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Passkey authentication failed.');
    }
  }

  async signInWithOidc(): Promise<void> {
    const issuer = this.oidcIssuerId.trim();
    if (!issuer) {
      this.errorMessage.set('Specify an configured Enterprise OIDC Issuer ID.');
      return;
    }
    this.errorMessage.set(null);
    try {
      const authUrl = await this.authStore.initiateOidcLogin(issuer);
      window.location.assign(authUrl);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'OIDC redirect failed.');
    }
  }
}
