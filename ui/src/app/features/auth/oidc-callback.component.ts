import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AuthStore } from '../../core/auth/auth.store';

@Component({
  selector: 'mc-oidc-callback',
  standalone: true,
  imports: [RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-oidc-callback-wrap">
      <div class="mc-card mc-oidc-card">
        <h2>Enterprise OIDC Sign-In</h2>
        @if (errorMessage(); as err) {
          <p class="mc-field-error" role="alert">{{ err }}</p>
          <a routerLink="/login" class="mc-btn mc-btn-primary">Return to Sign In</a>
        } @else {
          <p>Completing PKCE authorization code exchange...</p>
        }
      </div>
    </div>
  `,
  styles: `
    .mc-oidc-callback-wrap {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--mc-space-4);
    }

    .mc-oidc-card {
      padding: var(--mc-space-5);
      max-width: 420px;
      text-align: center;
    }
  `,
})
export class OidcCallbackComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly authStore = inject(AuthStore);

  readonly errorMessage = signal<string | null>(null);

  ngOnInit(): void {
    const code = this.route.snapshot.queryParamMap.get('code');
    const state = this.route.snapshot.queryParamMap.get('state');
    if (!code || !state) {
      this.errorMessage.set('Missing OIDC authorization code or state parameter.');
      return;
    }

    void this.finishOidc(code, state);
  }

  private async finishOidc(code: string, state: string): Promise<void> {
    try {
      await this.authStore.completeOidcCallback(code, state);
      await this.router.navigateByUrl('/app/gateway');
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'OIDC PKCE callback failed.');
    }
  }
}
