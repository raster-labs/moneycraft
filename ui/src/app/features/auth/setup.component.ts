import { HttpClient } from '@angular/common/http';
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
import { firstValueFrom } from 'rxjs';
import { AuthStore, AuthenticatedPrincipal } from '../../core/auth/auth.store';
import { PasskeyService } from '../../core/auth/passkey.service';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { ShortcutCommandRegistry } from '../../core/keyboard/shortcut-command.registry';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { GridCellNavDirective } from '../../shared/directives/grid-cell-nav.directive';
import { CompanyContextStore } from '../../state/company-context.store';

interface InitialiseInstallationResponse {
  readonly installationId: string;
  readonly ownerPrincipal: AuthenticatedPrincipal;
  readonly recoveryReceiptCode: string;
}

@Component({
  selector: 'mc-setup',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, GridCellNavDirective],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-auth-page" data-mc-nav-container>
      <div class="mc-card mc-setup-card">
        <div class="mc-setup-brand-header">
          <img
            src="assets/brand/moneycraft-logo-primary.svg"
            alt="MoneyCraft"
            width="210"
            height="38"
          />
          <span class="mc-badge mc-badge-jade">v1.0.0 &bull; Air-Gapped Ready</span>
        </div>

        <h1>First-Run Installation Owner Ceremony</h1>
        <p class="mc-setup-subtitle">
          Initialize the Protected Authority Database, generate wrapped Tink DEKs, and provision the
          primary <strong>INSTALLATION_OWNER</strong> principal. Zero license keys or internet
          activation required.
        </p>

        @if (recoveryReceipt(); as receipt) {
          <div class="mc-recovery-complete" role="region" aria-label="Recovery Kit Receipt">
            <h2>Installation Initialised Successfully</h2>
            <p>
              Record your one-time Administrative Recovery Receipt before proceeding to Company
              Creation:
            </p>
            <div class="mc-receipt-box">
              <mc-code-badge [code]="receipt" />
            </div>

            @if (passkeyService.isSupported() && !passkeyRegistered()) {
              <div class="mc-passkey-box">
                <button
                  type="button"
                  class="mc-btn"
                  [disabled]="submitting()"
                  (click)="enrollOwnerPasskey()"
                >
                  Enroll Hardware / Platform Passkey (WebAuthn FIDO2)
                </button>
              </div>
            } @else if (passkeyRegistered()) {
              <p class="mc-passkey-ok">FIDO2 Passkey enrolled for Owner step-up ceremonies.</p>
            }

            <label class="mc-checkbox-row">
              <input type="checkbox" [(ngModel)]="acknowledgedRecovery" name="ackRecovery" />
              <span>I have stored the recovery receipt in offline custody.</span>
            </label>

            <div class="mc-setup-actions">
              <button
                type="button"
                class="mc-btn mc-btn-primary"
                [disabled]="!acknowledgedRecovery"
                (click)="proceedToCompanyHub()"
              >
                Continue to Company Hub (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </div>
        } @else {
          <form (ngSubmit)="submitInitialisation()" class="mc-setup-form">
            <div class="mc-form-row">
              <label class="mc-field-label" for="setup-installation-label">
                Installation / Organization Label
              </label>
              <input
                id="setup-installation-label"
                type="text"
                name="installationLabel"
                class="mc-input"
                mcGridCellNav
                [(ngModel)]="installationLabel"
                required
              />
            </div>

            <div class="mc-form-grid-2">
              <div>
                <label class="mc-field-label" for="setup-owner-username">Owner Username</label>
                <input
                  id="setup-owner-username"
                  type="text"
                  name="username"
                  class="mc-input"
                  mcGridCellNav
                  autocomplete="username"
                  [(ngModel)]="ownerUsername"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="setup-owner-display">Owner Display Name</label>
                <input
                  id="setup-owner-display"
                  type="text"
                  name="displayName"
                  class="mc-input"
                  mcGridCellNav
                  [(ngModel)]="ownerDisplayName"
                  required
                />
              </div>
            </div>

            <div class="mc-form-grid-2">
              <div>
                <label class="mc-field-label" for="setup-password">
                  Master Password (Argon2id, min 12 chars)
                </label>
                <input
                  id="setup-password"
                  type="password"
                  name="password"
                  class="mc-input"
                  mcGridCellNav
                  autocomplete="new-password"
                  [(ngModel)]="password"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="setup-confirm-password">Confirm Password</label>
                <input
                  id="setup-confirm-password"
                  type="password"
                  name="confirmPassword"
                  class="mc-input"
                  mcGridCellNav
                  autocomplete="new-password"
                  [(ngModel)]="confirmPassword"
                  required
                />
              </div>
            </div>

            @if (errorMessage(); as err) {
              <div class="mc-field-error" role="alert">{{ err }}</div>
            }

            <div class="mc-setup-actions">
              <a routerLink="/login" class="mc-btn">Already Initialised? Sign In</a>
              <button type="submit" class="mc-btn mc-btn-primary" [disabled]="submitting()">
                Initialise Installation (<span class="mc-key-ctrl">A</span>)
              </button>
            </div>
          </form>
        }

        <div class="mc-copyright-line">
          MoneyCraft 1.0.0 &bull; Copyright 2026 Raster Images Pvt. Ltd.
        </div>
      </div>
    </div>
  `,
  styles: `
    .mc-auth-page {
      min-height: 100vh;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--mc-space-5);
      background: var(--mc-color-canvas);
    }

    .mc-setup-card {
      width: min(620px, 96vw);
      padding: var(--mc-space-6);
    }

    .mc-setup-brand-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      margin-bottom: var(--mc-space-4);
    }

    .mc-setup-subtitle {
      color: var(--mc-color-slate-600);
      margin: var(--mc-space-2) 0 var(--mc-space-4);
    }

    .mc-setup-form {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-form-grid-2 {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 640px) {
      .mc-form-grid-2 {
        grid-template-columns: 1fr;
      }
    }

    .mc-recovery-complete {
      padding: var(--mc-space-4);
      background: var(--mc-color-teal-50);
      border: 1px solid var(--mc-color-verdigris-teal);
      border-radius: var(--mc-radius-sm);
    }

    .mc-receipt-box {
      margin: var(--mc-space-3) 0;
    }

    .mc-passkey-box {
      margin: var(--mc-space-3) 0;
    }

    .mc-passkey-ok {
      color: var(--mc-color-success-700);
      font-weight: 600;
    }

    .mc-checkbox-row {
      display: flex;
      align-items: center;
      gap: var(--mc-space-2);
      margin: var(--mc-space-3) 0;
      font-weight: 600;
    }

    .mc-setup-actions {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-3);
      margin-top: var(--mc-space-3);
    }

    .mc-copyright-line {
      margin-top: var(--mc-space-5);
      padding-top: var(--mc-space-3);
      border-top: var(--mc-border-subtle);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-500);
      text-align: center;
    }
  `,
})
export class SetupComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly authStore = inject(AuthStore);
  private readonly companyContext = inject(CompanyContextStore);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  readonly passkeyService = inject(PasskeyService);

  installationLabel = 'MoneyCraft Primary Installation';
  ownerUsername = 'owner';
  ownerDisplayName = 'Installation Owner';
  password = '';
  confirmPassword = '';
  acknowledgedRecovery = false;

  readonly submitting = signal<boolean>(false);
  readonly errorMessage = signal<string | null>(null);
  readonly recoveryReceipt = signal<string | null>(null);
  readonly passkeyRegistered = signal<boolean>(false);

  ngOnInit(): void {
    const dispose = this.shortcutRegistry.bindHandler('screen.accept', 'SCREEN', () => {
      if (this.recoveryReceipt()) {
        if (this.acknowledgedRecovery) {
          this.proceedToCompanyHub();
        }
      } else {
        void this.submitInitialisation();
      }
    });
    this.destroyRef.onDestroy(dispose);
  }

  async submitInitialisation(): Promise<void> {
    if (this.password.length < 12) {
      this.errorMessage.set('Master password must be at least 12 characters long.');
      return;
    }
    if (this.password !== this.confirmPassword) {
      this.errorMessage.set('Password confirmation does not match.');
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);
    try {
      const response = await firstValueFrom(
        this.http.post<InitialiseInstallationResponse>(
          this.runtimeConfig.url('/system/initialise'),
          {
            installationLabel: this.installationLabel.trim(),
            ownerUsername: this.ownerUsername.trim(),
            ownerDisplayName: this.ownerDisplayName.trim(),
            password: this.password,
          },
          { withCredentials: true },
        ),
      );

      this.companyContext.setSystemStatus('ACTIVE');
      this.authStore.setAuthenticatedPrincipal(response.ownerPrincipal);
      this.recoveryReceipt.set(response.recoveryReceiptCode);
    } catch (err: unknown) {
      this.errorMessage.set(
        err instanceof Error ? err.message : 'Installation bootstrap ceremony failed.',
      );
    } finally {
      this.submitting.set(false);
    }
  }

  async enrollOwnerPasskey(): Promise<void> {
    this.submitting.set(true);
    try {
      await this.passkeyService.registerPasskey(`${this.ownerUsername}-primary-passkey`);
      this.passkeyRegistered.set(true);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Passkey enrollment failed.');
    } finally {
      this.submitting.set(false);
    }
  }

  proceedToCompanyHub(): void {
    void this.router.navigateByUrl('/app/companies');
  }
}
