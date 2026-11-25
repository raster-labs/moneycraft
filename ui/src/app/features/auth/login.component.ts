import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  OnInit,
  computed,
  inject,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';
import { PasskeyService } from '../../core/auth/passkey.service';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { HelpPanelComponent } from '../../shared/components/contextual-help/help-panel.component';
import { KeyboardSettingsModalComponent } from '../../shared/components/keyboard-settings-modal/keyboard-settings-modal.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';

@Component({
  selector: 'mc-login',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    GridCellNavDirective,
    CodeBadgeComponent,
    SemanticIconComponent,
    KeyboardSettingsModalComponent,
    HelpPanelComponent,
  ],
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
          <div class="mc-login-brand-actions">
            <button
              type="button"
              class="mc-btn mc-btn-secondary mc-kbd-trigger-btn"
              data-testid="login-api-config-toggle-btn"
              (click)="toggleApiConfigPanel()"
            >
              <span>Server IP &amp; Port</span>
            </button>
            <button
              type="button"
              class="mc-btn mc-btn-secondary mc-kbd-trigger-btn"
              data-testid="login-keyboard-settings-btn"
              (click)="openKeyboardSettings()"
            >
              <mc-icon name="keyboard" [size]="16" />
              <span>Keyboard settings</span>
            </button>
            <span class="mc-badge mc-badge-slate">v1.0.0</span>
          </div>
        </div>

        @if (apiConfigOpen()) {
          <div class="mc-api-config-box" data-testid="login-api-config-panel" role="region" aria-label="Backend API Server IP and Port Configuration">
            <div class="mc-api-config-head">
              <strong>Backend API Server (IP &amp; Port)</strong>
              <mc-code-badge [code]="runtimeConfig.apiBaseUrl" data-testid="login-active-api-url" />
            </div>
            <div class="mc-api-config-grid">
              <div>
                <label class="mc-field-label" for="mc-login-api-scheme">Protocol</label>
                <select
                  id="mc-login-api-scheme"
                  name="apiScheme"
                  class="mc-select"
                  data-testid="login-api-scheme-select"
                  [(ngModel)]="apiScheme"
                >
                  <option value="https">HTTPS (TLS)</option>
                  <option value="http">HTTP (Loopback / Dev)</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="mc-login-api-host">Backend IP / Host</label>
                <input
                  id="mc-login-api-host"
                  type="text"
                  name="apiHost"
                  class="mc-input mc-code"
                  data-testid="login-api-host-input"
                  [(ngModel)]="apiHost"
                  placeholder="192.168.1.100 or localhost"
                />
              </div>
              <div>
                <label class="mc-field-label" for="mc-login-api-port">Port</label>
                <input
                  id="mc-login-api-port"
                  type="text"
                  name="apiPort"
                  class="mc-input mc-code"
                  data-testid="login-api-port-input"
                  [(ngModel)]="apiPort"
                  placeholder="8443"
                />
              </div>
            </div>
            @if (apiConfigMessage(); as apiMsg) {
              <div class="mc-api-config-msg" data-testid="login-api-config-msg">{{ apiMsg }}</div>
            }
            <div class="mc-api-config-actions">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                data-testid="login-api-apply-btn"
                (click)="applyBackendApiConfig()"
              >
                Apply IP &amp; Port
              </button>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="login-api-reset-btn"
                (click)="resetBackendApiConfig()"
              >
                Reset Default (/api/v1)
              </button>
            </div>
          </div>
        }

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
            <div class="mc-status-banner mc-status-error" role="alert">
              <mc-icon name="error" [size]="20" />
              <span>{{ err }}</span>
            </div>
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
            class="mc-btn mc-btn-secondary"
            [disabled]="!passkeyService.isSupported() || authStore.loading()"
            (click)="signInWithPasskey()"
          >
            Sign In with Passkey (FIDO2)
          </button>

          @if (enabledOidcIssuers().length > 0) {
            <div class="mc-oidc-configured-list" aria-label="Configured Enterprise OIDC Providers">
              @for (issuer of enabledOidcIssuers(); track issuer.issuerId) {
                <button
                  type="button"
                  class="mc-btn mc-btn-secondary mc-oidc-issuer-btn"
                  [disabled]="authStore.loading()"
                  [attr.data-testid]="'oidc-issuer-btn-' + issuer.issuerId"
                  (click)="signInWithConfiguredIssuer(issuer.issuerId)"
                >
                  <span>Continue with {{ issuer.displayName }}</span>
                </button>
              }
            </div>
          }

          <div class="mc-oidc-row">
            <input
              type="text"
              class="mc-input"
              [(ngModel)]="oidcIssuerId"
              name="oidcIssuerId"
              placeholder="OIDC Issuer ID (e.g. corporate-sso)"
              aria-label="Enterprise OIDC Issuer ID"
            />
            <button type="button" class="mc-btn mc-btn-secondary" (click)="signInWithOidc()">
              OIDC SSO
            </button>
          </div>
        </div>

        <div class="mc-login-footer">
          <a routerLink="/setup" class="mc-setup-link">First-Run Setup Ceremony</a>
          <span>Raster Images Pvt. Ltd.</span>
        </div>
      </div>

      <mc-keyboard-settings-modal />
      <mc-help-panel />
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
      width: min(460px, 94vw);
      padding: var(--mc-space-6);
    }

    .mc-login-brand {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
      margin-bottom: var(--mc-space-4);
    }

    .mc-login-brand-actions {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
    }

    .mc-kbd-trigger-btn {
      font-size: var(--mc-font-size-xs);
      padding: 0 0.55rem;
    }

    .mc-api-config-box {
      margin-bottom: var(--mc-space-4);
      padding: var(--mc-space-3);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-sm);
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-api-config-head {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      flex-wrap: wrap;
      font-size: var(--mc-font-size-xs);
    }

    .mc-api-config-grid {
      display: grid;
      grid-template-columns: 120px 1fr 90px;
      gap: var(--mc-space-2);
    }

    .mc-api-config-msg {
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-pressed-teal);
    }

    .mc-api-config-actions {
      display: flex;
      justify-content: flex-end;
      gap: var(--mc-space-2);
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

    .mc-oidc-configured-list {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-oidc-issuer-btn {
      width: 100%;
      justify-content: center;
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
      color: var(--mc-color-pressed-teal);
      font-weight: 600;
      text-decoration: none;
    }
  `,
})
export class LoginComponent implements OnInit {
  readonly authStore = inject(AuthStore);
  readonly passkeyService = inject(PasskeyService);
  readonly runtimeConfig = inject(RuntimeConfigService);
  readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute, { optional: true });
  private readonly destroyRef = inject(DestroyRef);

  username = '';
  password = '';
  oidcIssuerId = '';
  apiScheme: 'https' | 'http' = 'https';
  apiHost = 'localhost';
  apiPort = '8443';
  readonly apiConfigOpen = signal<boolean>(false);
  readonly apiConfigMessage = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);

  readonly enabledOidcIssuers = computed(() =>
    this.authStore.oidcIssuers().filter((issuer) => issuer.enabled !== false && issuer.issuerId.length > 0),
  );

  ngOnInit(): void {
    this.syncApiFieldsFromRuntimeConfig();
    void this.authStore.loadPublicOidcIssuers();

    const dispose = this.shortcutRegistry.bindHandler('screen.accept', 'SCREEN', () => {
      void this.signInWithPassword();
    });
    this.destroyRef.onDestroy(dispose);
  }

  toggleApiConfigPanel(): void {
    this.syncApiFieldsFromRuntimeConfig();
    this.apiConfigMessage.set(null);
    this.apiConfigOpen.update((v) => !v);
  }

  applyBackendApiConfig(): void {
    try {
      const updated = this.runtimeConfig.setBackendHostAndPort(
        this.apiHost,
        this.apiPort,
        this.apiScheme,
      );
      this.apiConfigMessage.set(`Backend API set to ${updated.apiBaseUrl}`);
      void this.authStore.loadPublicOidcIssuers();
    } catch (err: unknown) {
      this.apiConfigMessage.set(
        err instanceof Error ? err.message : 'Invalid backend IP or port configuration.',
      );
    }
  }

  resetBackendApiConfig(): void {
    const restored = this.runtimeConfig.resetBackendEndpointToDefault();
    this.syncApiFieldsFromRuntimeConfig();
    this.apiConfigMessage.set(`Restored default endpoint (${restored.apiBaseUrl})`);
    void this.authStore.loadPublicOidcIssuers();
  }

  private syncApiFieldsFromRuntimeConfig(): void {
    const parts = this.runtimeConfig.currentEndpointParts();
    this.apiScheme = parts.scheme;
    this.apiHost = parts.host;
    this.apiPort = parts.port;
  }

  openKeyboardSettings(): void {
    this.shortcutRegistry.openKeyboardSettingsModal();
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
      await this.completePostAuthenticationRedirect();
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
      await this.completePostAuthenticationRedirect();
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Passkey authentication failed.');
    }
  }

  navigateToNativeAuthorize(url: string): void {
    window.location.assign(url);
  }

  private async completePostAuthenticationRedirect(): Promise<void> {
    const nativeAuthorize = this.route?.snapshot.queryParamMap.get('nativeAuthorize')?.trim() ?? '';
    if (
      nativeAuthorize.startsWith('/api/v1/auth/native/authorize') &&
      !nativeAuthorize.startsWith('//')
    ) {
      const targetUrl = this.runtimeConfig.hasCustomEndpointOverride()
        ? this.runtimeConfig.url(nativeAuthorize.replace(/^\/api\/v1/, ''))
        : nativeAuthorize;
      this.navigateToNativeAuthorize(targetUrl);
      return;
    }
    await this.router.navigateByUrl('/app/gateway');
  }

  async signInWithConfiguredIssuer(issuerId: string): Promise<void> {
    this.oidcIssuerId = issuerId;
    await this.signInWithOidc();
  }

  async signInWithOidc(): Promise<void> {
    const issuer = this.oidcIssuerId.trim();
    if (!issuer) {
      this.errorMessage.set('Specify a configured Enterprise OIDC Issuer ID.');
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
