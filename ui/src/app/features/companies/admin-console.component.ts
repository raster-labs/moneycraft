import { HttpClient } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { AuthStore, CompanyRole, OidcIssuerInfo, SystemRole } from '../../core/auth/auth.store';
import { RuntimeConfigService } from '../../core/http/runtime-config.service';
import { CodeBadgeComponent } from '../../shared/components/code-badge/code-badge.component';
import { SemanticIconComponent } from '../../shared/components/semantic-icon/semantic-icon.component';
import { CompanyContextStore } from '../../state/company-context.store';

export interface AdminPrincipalRecord {
  readonly id: string;
  readonly username: string;
  readonly displayName: string;
  readonly systemRoles: readonly SystemRole[];
  readonly active: boolean;
}

export interface CreatedServiceKeyResponse {
  readonly keyId: string;
  readonly label: string;
  readonly keyPrefix: string;
  readonly rawSecretOnce: string;
  readonly expiresAt: string | null;
}

export interface AuditChainVerifyResponse {
  readonly verified: boolean;
  readonly checkedEventCount: number;
  readonly tailHashSha256: string;
  readonly verifiedAt: string;
}

@Component({
  selector: 'mc-admin-console',
  standalone: true,
  imports: [FormsModule, RouterLink, CodeBadgeComponent, SemanticIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mc-admin-layout">
      <div class="mc-admin-header">
        <div>
          <h1>Administration, RBAC, OIDC Federation, Service Keys &amp; Security Chain</h1>
          <p class="mc-admin-sub">
            Manage Protected Authority DB principals, company role grants (<strong>MC-OBJECT-1</strong>),
            Enterprise OIDC federation status (<strong>MC-FED-01</strong>), machine-to-machine API keys,
            <strong>MC-AUDIT-2</strong> SHA-256 chain integrity, and Quarantine DR controls.
          </p>
        </div>
        @if (authStore.isSuperAdmin()) {
          <a
            routerLink="/app/admin/government-integrations"
            class="mc-btn mc-btn-primary"
            data-testid="admin-gov-integrations-link"
          >
            <mc-icon name="approve-shield" [size]="16" />
            <span>Government &amp; Connected Banking Vault (SUPER_ADMIN)</span>
          </a>
        } @else {
          <span class="mc-badge mc-badge-slate" data-testid="admin-gov-superadmin-notice">
            Govt / Banking Vault: SUPER_ADMIN Only
          </span>
        }
      </div>

      @if (statusMessage(); as msg) {
        <div class="mc-status-banner mc-status-confirmed" role="status">
          <mc-icon name="confirmed" [size]="20" />
          <span>{{ msg }}</span>
        </div>
      }
      @if (errorMessage(); as err) {
        <div class="mc-status-banner mc-status-error" role="alert">
          <mc-icon name="error" [size]="20" />
          <span>{{ err }}</span>
        </div>
      }

      <div class="mc-admin-grid">
        <!-- 1. Principals & Company Role Grants -->
        <section class="mc-card mc-admin-card" aria-label="Principals and Role Grants">
          <h2>1. Provision Principal &amp; Company Role Grant</h2>
          <form (ngSubmit)="createPrincipal()" class="mc-stack-form">
            <div class="mc-two-col">
              <div>
                <label class="mc-field-label" for="adm-username">Username</label>
                <input
                  id="adm-username"
                  type="text"
                  name="newUsername"
                  class="mc-input"
                  [(ngModel)]="newUsername"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="adm-display">Display Name</label>
                <input
                  id="adm-display"
                  type="text"
                  name="newDisplayName"
                  class="mc-input"
                  [(ngModel)]="newDisplayName"
                  required
                />
              </div>
            </div>

            <div class="mc-two-col">
              <div>
                <label class="mc-field-label" for="adm-pass">Initial Argon2id Password</label>
                <input
                  id="adm-pass"
                  type="password"
                  name="newPassword"
                  class="mc-input"
                  [(ngModel)]="newPassword"
                  required
                />
              </div>
              <div>
                <label class="mc-field-label" for="adm-sys-role">System Role</label>
                <select
                  id="adm-sys-role"
                  name="newSystemRole"
                  class="mc-select"
                  [(ngModel)]="newSystemRole"
                >
                  <option value="">None (Company Scoped Only)</option>
                  <option value="SYSTEM_OPERATOR">SYSTEM_OPERATOR</option>
                  <option value="SECURITY_AUDITOR">SECURITY_AUDITOR</option>
                  <option value="INSTALLATION_OWNER">INSTALLATION_OWNER</option>
                </select>
              </div>
            </div>

            <button type="submit" class="mc-btn mc-btn-primary">Create Principal</button>
          </form>

          <hr class="mc-divider" />

          <h3>Assign Company Role Grant</h3>
          <form (ngSubmit)="assignCompanyGrant()" class="mc-stack-form">
            <div class="mc-two-col">
              <div>
                <label class="mc-field-label" for="grant-sec-preset">
                  TallyPrime Gold Security Level Preset
                </label>
                <select
                  id="grant-sec-preset"
                  name="securityLevelPreset"
                  class="mc-select"
                  data-testid="grant-security-preset-select"
                  [ngModel]="securityLevelPreset"
                  (ngModelChange)="applySecurityLevelPreset($event)"
                >
                  <option value="CUSTOM">Custom Security Level</option>
                  <option value="OWNER">Owner (Full Access)</option>
                  <option value="DATA_ENTRY">Data Entry (Restricted Back-Dated &amp; No Export)</option>
                  <option value="TALLY_NET_USER">Tally.NET User (Standard Remote Access)</option>
                  <option value="TALLY_NET_AUDITOR">Tally.NET Auditor (Audit &amp; Journal Access)</option>
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="grant-principal-id">Principal ID / Username</label>
                <input
                  id="grant-principal-id"
                  type="text"
                  name="grantPrincipalId"
                  class="mc-input mc-code"
                  data-testid="grant-principal-input"
                  [(ngModel)]="grantPrincipalId"
                  required
                />
              </div>
            </div>

            <div class="mc-two-col">
              <div>
                <label class="mc-field-label" for="grant-company">Target Company</label>
                <select
                  id="grant-company"
                  name="grantCompanyId"
                  class="mc-select"
                  [(ngModel)]="grantCompanyId"
                >
                  @for (comp of companyContext.openCompanies(); track comp.id) {
                    <option [value]="comp.id">{{ comp.legalName }} ({{ comp.code }})</option>
                  }
                </select>
              </div>
              <div>
                <label class="mc-field-label" for="grant-role">Company Role</label>
                <select
                  id="grant-role"
                  name="grantRole"
                  class="mc-select"
                  data-testid="grant-role-select"
                  [(ngModel)]="grantRole"
                >
                  @for (r of companyRoles; track r) {
                    <option [value]="r">{{ r }}</option>
                  }
                </select>
              </div>
            </div>

            <div class="mc-two-col">
              <div>
                <label class="mc-field-label" for="grant-backdate">Back-Dated Days Limit</label>
                <input
                  id="grant-backdate"
                  type="number"
                  min="0"
                  name="grantBackdatedDays"
                  class="mc-input"
                  data-testid="grant-backdated-days-input"
                  [(ngModel)]="grantBackdatedDays"
                />
              </div>
              <div>
                <label class="mc-field-label" for="grant-vtypes">
                  Allowed Voucher Types (Comma-Separated, Blank = All)
                </label>
                <input
                  id="grant-vtypes"
                  type="text"
                  name="grantVoucherTypeAllowlistInput"
                  class="mc-input"
                  data-testid="grant-voucher-types-input"
                  [(ngModel)]="grantVoucherTypeAllowlistInput"
                  placeholder="e.g. Sales, Receipt"
                />
              </div>
            </div>

            <div>
              <label class="mc-field-label" for="grant-export-perm">
                <input
                  id="grant-export-perm"
                  type="checkbox"
                  name="grantExportPermitted"
                  data-testid="grant-export-permitted-checkbox"
                  [(ngModel)]="grantExportPermitted"
                />
                <span>Allow Print / Export / Share (exportPermitted)</span>
              </label>
            </div>

            <button
              type="submit"
              class="mc-btn mc-btn-secondary"
              data-testid="grant-save-btn"
            >
              Save Company Role Grant
            </button>
          </form>

          <hr class="mc-divider" />

          <!-- 5. Enterprise OIDC Federation Status (MC-FED-01) -->
          <div aria-label="Enterprise OIDC Federation Status" data-testid="admin-oidc-federation-card">
            <div class="mc-oidc-header-row">
              <h3>5. Enterprise OIDC Federation Status (MC-FED-01)</h3>
              <button
                type="button"
                class="mc-btn mc-btn-secondary"
                data-testid="admin-refresh-oidc-btn"
                (click)="loadOidcFederationStatus()"
              >
                <mc-icon name="refresh" [size]="16" />
                <span>Refresh</span>
              </button>
            </div>
            <p class="mc-card-note">
              Authentication-only external IdP federation; Protected Authority DB controls all MoneyCraft
              roles. Local break-glass Argon2id principals remain active if external IdP is unreachable.
            </p>

            @if (oidcIssuers().length === 0) {
              <div class="mc-empty-records">No matching records</div>
            } @else {
              <table class="mc-table mc-table-dense" aria-label="Configured OIDC Issuers">
                <thead>
                  <tr>
                    <th>Issuer ID</th>
                    <th>Display Name</th>
                    <th>PKCE</th>
                    <th>Lifecycle</th>
                    <th>State</th>
                  </tr>
                </thead>
                <tbody>
                  @for (iss of oidcIssuers(); track iss.issuerId) {
                    <tr>
                      <td class="mc-code">{{ iss.issuerId }}</td>
                      <td>{{ iss.displayName }}</td>
                      <td>{{ iss.pkceRequired !== false ? 'S256' : 'Standard' }}</td>
                      <td>{{ iss.lifecycleProfile ?? 'MANUAL_GRANT' }}</td>
                      <td>
                        @if (iss.enabled !== false) {
                          <span class="mc-badge mc-badge-jade">ENABLED</span>
                        } @else {
                          <span class="mc-badge mc-badge-slate">DISABLED</span>
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          </div>
        </section>

        <!-- 2. Service API Keys, MC-AUDIT-2 Verifier & Quarantine DR -->
        <div class="mc-right-stack">
          <section class="mc-card mc-admin-card" aria-label="Service API Keys">
            <h2>2. Scoped Machine-to-Machine Service Keys</h2>
            <form (ngSubmit)="issueServiceKey()" class="mc-stack-form">
              <div class="mc-two-col">
                <div>
                  <label class="mc-field-label" for="sk-label">Key Label</label>
                  <input
                    id="sk-label"
                    type="text"
                    name="serviceKeyLabel"
                    class="mc-input"
                    [(ngModel)]="serviceKeyLabel"
                    placeholder="e.g. nightly-xml-exporter"
                    required
                  />
                </div>
                <div>
                  <label class="mc-field-label" for="sk-expiry">Expires On (Optional)</label>
                  <input
                    id="sk-expiry"
                    type="date"
                    name="serviceKeyExpiry"
                    class="mc-input"
                    [(ngModel)]="serviceKeyExpiry"
                  />
                </div>
              </div>
              <button type="submit" class="mc-btn mc-btn-secondary">Issue Scoped Service Key</button>
            </form>

            @if (issuedServiceKey(); as issued) {
              <div class="mc-issued-key-box" role="region" aria-label="Issued Service Key">
                <div class="mc-issued-key-warn">
                  Copy secret now (stored hashed in <code>ma_service_key</code>; never shown again):
                </div>
                <mc-code-badge [code]="issued.rawSecretOnce" />
              </div>
            }
          </section>

          <section class="mc-card mc-admin-card" aria-label="MC-AUDIT-2 Cryptographic Chain">
            <h2>3. MC-AUDIT-2 Hash-Chain Verifier</h2>
            <p class="mc-card-note">
              Verifies continuous <code>SHA256(prev_hash || RFC8785_JCS(event))</code> integrity
              across installation and company audit streams.
            </p>
            <button type="button" class="mc-btn mc-btn-primary" (click)="verifyAuditChain()">
              <mc-icon name="approve-shield" [size]="16" />
              <span>Verify MC-AUDIT-2 Chain Now</span>
            </button>

            @if (auditVerification(); as res) {
              <div class="mc-audit-result">
                <div>
                  Status:
                  @if (res.verified) {
                    <span class="mc-badge mc-badge-jade">INTACT ({{ res.checkedEventCount }} events)</span>
                  } @else {
                    <span class="mc-badge mc-badge-danger">TAMPERING DETECTED</span>
                  }
                </div>
                <div class="mc-audit-tail-line">
                  <span>Tail SHA-256:</span>
                  <mc-code-badge [code]="res.tailHashSha256" />
                </div>
              </div>
            }
          </section>

          <section class="mc-card mc-admin-card mc-quarantine-card" aria-label="Quarantine DR Controls">
            <h2>4. Quarantine Disaster-Recovery Mode (FND-DR-01)</h2>
            <p class="mc-card-note">
              Current Security State:
              @if (companyContext.quarantined()) {
                <span class="mc-badge mc-badge-danger">QUARANTINED</span>
              } @else {
                <span class="mc-badge mc-badge-jade">{{ companyContext.systemStatus() }}</span>
              }
            </p>
            <div class="mc-two-col">
              <input
                type="text"
                class="mc-input"
                [(ngModel)]="quarantineReasonInput"
                name="quarantineReason"
                placeholder="Reason / Recovery Receipt Reference"
                aria-label="Quarantine Reason or Receipt"
              />
              <div class="mc-quarantine-btns">
                <button type="button" class="mc-btn mc-btn-danger" (click)="enterQuarantine()">
                  Enter Quarantine
                </button>
                <button type="button" class="mc-btn mc-btn-secondary" (click)="recoverFromQuarantine()">
                  <mc-icon name="refresh" [size]="16" />
                  <span>Recover</span>
                </button>
              </div>
            </div>
          </section>
        </div>
      </div>
    </div>
  `,
  styles: `
    .mc-admin-layout {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-4);
    }

    .mc-admin-sub {
      margin: 4px 0 0;
      color: var(--mc-color-slate-600);
      font-size: var(--mc-font-size-sm);
    }

    .mc-admin-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-4);
      align-items: start;
    }

    @media (max-width: 980px) {
      .mc-admin-grid {
        grid-template-columns: 1fr;
      }
    }

    .mc-right-stack {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-4);
    }

    .mc-admin-card {
      padding: var(--mc-space-4);

      h2 {
        font-size: var(--mc-font-size-md);
        margin-bottom: var(--mc-space-3);
      }

      h3 {
        font-size: var(--mc-font-size-sm);
        margin-bottom: var(--mc-space-2);
      }
    }

    .mc-oidc-header-row {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: var(--mc-space-2);
      margin-bottom: var(--mc-space-1);

      h3 {
        margin-bottom: 0;
      }
    }

    .mc-stack-form {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-two-col {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-2);
    }

    .mc-divider {
      border: 0;
      border-top: var(--mc-border-subtle);
      margin: var(--mc-space-4) 0;
    }

    .mc-card-note {
      margin: 0 0 var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
    }

    .mc-issued-key-box {
      margin-top: var(--mc-space-3);
      padding: var(--mc-space-2);
      background: var(--mc-color-warning-50);
      border: 1px solid var(--mc-color-warning-100);
      border-radius: var(--mc-radius-xs);
    }

    .mc-issued-key-warn {
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
      color: var(--mc-color-warning-700);
      margin-bottom: 4px;
    }

    .mc-audit-result {
      margin-top: var(--mc-space-3);
      padding: var(--mc-space-2);
      background: var(--mc-color-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
      display: flex;
      flex-direction: column;
      gap: 6px;
      font-size: var(--mc-font-size-xs);
      font-weight: 600;
    }

    .mc-audit-tail-line {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-wrap: wrap;
    }

    .mc-quarantine-card {
      border-color: var(--mc-color-danger-100);
    }

    .mc-quarantine-btns {
      display: flex;
      gap: 6px;
    }
  `,
})
export class AdminConsoleComponent implements OnInit {
  readonly companyContext = inject(CompanyContextStore);
  private readonly authStore = inject(AuthStore);
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);

  readonly companyRoles: readonly CompanyRole[] = [
    'COMPANY_ADMIN',
    'AUDITOR_APPROVER',
    'SENIOR_ACCOUNTANT',
    'DATA_ENTRY_OPERATOR',
    'INVENTORY_CLERK',
    'READ_ONLY_VIEWER',
  ];

  newUsername = '';
  newDisplayName = '';
  newPassword = '';
  newSystemRole = '';

  securityLevelPreset: 'CUSTOM' | 'OWNER' | 'DATA_ENTRY' | 'TALLY_NET_USER' | 'TALLY_NET_AUDITOR' =
    'CUSTOM';
  grantPrincipalId = '';
  grantCompanyId = '';
  grantRole: CompanyRole = 'SENIOR_ACCOUNTANT';
  grantBackdatedDays = 7;
  grantVoucherTypeAllowlistInput = '';
  grantExportPermitted = true;

  serviceKeyLabel = '';
  serviceKeyExpiry = '';
  quarantineReasonInput = '';

  readonly statusMessage = signal<string | null>(null);
  readonly errorMessage = signal<string | null>(null);
  readonly principals = signal<readonly AdminPrincipalRecord[]>([]);
  readonly oidcIssuers = signal<readonly OidcIssuerInfo[]>([]);
  readonly issuedServiceKey = signal<CreatedServiceKeyResponse | null>(null);
  readonly auditVerification = signal<AuditChainVerifyResponse | null>(null);

  ngOnInit(): void {
    const active = this.companyContext.activeCompany();
    if (active) {
      this.grantCompanyId = active.id;
    }
    void this.loadPrincipals();
    void this.loadOidcFederationStatus();
  }

  async loadPrincipals(): Promise<void> {
    try {
      const list = await firstValueFrom(
        this.http.get<readonly AdminPrincipalRecord[]>(
          this.runtimeConfig.url('/admin/principals'),
          { withCredentials: true },
        ),
      );
      this.principals.set(list);
      if (!this.grantPrincipalId && list.length > 0) {
        this.grantPrincipalId = list[0].id;
      }
    } catch {
      // Non-fatal if principal directory is unavailable
    }
  }

  async loadOidcFederationStatus(): Promise<void> {
    try {
      const adminList = await firstValueFrom(
        this.http.get<readonly Record<string, unknown>[]>(
          this.runtimeConfig.url('/admin/oidc-issuers'),
          { withCredentials: true },
        ),
      );
      if (Array.isArray(adminList)) {
        this.oidcIssuers.set(
          adminList.map((raw) => {
            const idVal = String(raw['issuerId'] ?? raw['id'] ?? raw['registrationId'] ?? '');
            return {
              id: idVal,
              issuerId: idVal,
              registrationId: raw['registrationId'] ? String(raw['registrationId']) : idVal,
              displayName: String(raw['displayName'] ?? idVal),
              issuerUri: raw['issuerUri'] ? String(raw['issuerUri']) : undefined,
              clientId: raw['clientId'] ? String(raw['clientId']) : undefined,
              pkceRequired: raw['pkceRequired'] !== undefined ? Boolean(raw['pkceRequired']) : true,
              enabled: raw['enabled'] !== undefined ? Boolean(raw['enabled']) : true,
              lifecycleProfile: raw['lifecycleProfile'] ? String(raw['lifecycleProfile']) : null,
              checkedAt: raw['checkedAt'] ? String(raw['checkedAt']) : null,
            };
          }),
        );
        return;
      }
    } catch {
      // Fallback to public OIDC issuer directory (`GET /api/v1/auth/oidc/issuers`)
    }
    const pubList = await this.authStore.loadPublicOidcIssuers();
    this.oidcIssuers.set(pubList);
  }

  async createPrincipal(): Promise<void> {
    this.errorMessage.set(null);
    this.statusMessage.set(null);
    try {
      const created = await firstValueFrom(
        this.http.post<AdminPrincipalRecord>(
          this.runtimeConfig.url('/admin/principals'),
          {
            username: this.newUsername.trim(),
            displayName: this.newDisplayName.trim(),
            password: this.newPassword,
            systemRoles: this.newSystemRole ? [this.newSystemRole] : [],
          },
          { withCredentials: true },
        ),
      );
      this.grantPrincipalId = created.id;
      this.principals.set([...this.principals(), created]);
      this.newPassword = '';
      this.statusMessage.set(`Created principal "${created.username}" (${created.id}).`);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Failed to create principal.');
    }
  }

  applySecurityLevelPreset(
    preset: 'CUSTOM' | 'OWNER' | 'DATA_ENTRY' | 'TALLY_NET_USER' | 'TALLY_NET_AUDITOR',
  ): void {
    this.securityLevelPreset = preset;
    if (preset === 'OWNER') {
      this.grantRole = 'COMPANY_ADMIN';
      this.grantBackdatedDays = 365;
      this.grantVoucherTypeAllowlistInput = '';
      this.grantExportPermitted = true;
    } else if (preset === 'DATA_ENTRY') {
      this.grantRole = 'DATA_ENTRY_OPERATOR';
      this.grantBackdatedDays = 7;
      this.grantVoucherTypeAllowlistInput = 'Sales, Purchase, Receipt, Payment';
      this.grantExportPermitted = false;
    } else if (preset === 'TALLY_NET_USER') {
      this.grantRole = 'SENIOR_ACCOUNTANT';
      this.grantBackdatedDays = 30;
      this.grantVoucherTypeAllowlistInput = '';
      this.grantExportPermitted = true;
    } else if (preset === 'TALLY_NET_AUDITOR') {
      this.grantRole = 'AUDITOR_APPROVER';
      this.grantBackdatedDays = 0;
      this.grantVoucherTypeAllowlistInput = 'Journal';
      this.grantExportPermitted = true;
    }
  }

  async assignCompanyGrant(): Promise<void> {
    if (!this.grantPrincipalId.trim() || !this.grantCompanyId) {
      this.errorMessage.set('Specify both Principal ID and Target Company.');
      return;
    }
    this.errorMessage.set(null);
    this.statusMessage.set(null);
    const parsedAllowlist = this.grantVoucherTypeAllowlistInput
      .split(',')
      .map((item) => item.trim())
      .filter((item) => item.length > 0);
    try {
      await firstValueFrom(
        this.http.put<void>(
          this.runtimeConfig.url(
            `/admin/principals/${encodeURIComponent(this.grantPrincipalId.trim())}/grants`,
          ),
          {
            companyId: this.grantCompanyId,
            roleCode: this.grantRole,
            voucherTypeAllowlist: parsedAllowlist,
            backdatedDaysLimit: this.grantBackdatedDays,
            exportPermitted: this.grantExportPermitted,
          },
          { withCredentials: true },
        ),
      );
      this.statusMessage.set(`Updated ${this.grantRole} grant on company.`);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Failed to update role grant.');
    }
  }

  async issueServiceKey(): Promise<void> {
    if (!this.serviceKeyLabel.trim()) {
      this.errorMessage.set('Provide a Service Key label.');
      return;
    }
    this.errorMessage.set(null);
    const isoExpiresAt = this.serviceKeyExpiry
      ? this.serviceKeyExpiry.includes('T')
        ? this.serviceKeyExpiry
        : `${this.serviceKeyExpiry}T23:59:59Z`
      : null;
    try {
      const issued = await firstValueFrom(
        this.http.post<CreatedServiceKeyResponse>(
          this.runtimeConfig.url('/admin/service-keys'),
          {
            label: this.serviceKeyLabel.trim(),
            companyIds: this.grantCompanyId ? [this.grantCompanyId] : [],
            endpointAllowlist: [],
            expiresAt: isoExpiresAt,
          },
          { withCredentials: true },
        ),
      );
      this.issuedServiceKey.set(issued);
      this.statusMessage.set(`Issued service API key (${issued.keyPrefix}).`);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Service key creation failed.');
    }
  }

  async verifyAuditChain(): Promise<void> {
    this.errorMessage.set(null);
    try {
      const result = await firstValueFrom(
        this.http.get<AuditChainVerifyResponse>(this.runtimeConfig.url('/admin/audit/verify'), {
          withCredentials: true,
        }),
      );
      this.auditVerification.set(result);
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Audit chain verification failed.');
    }
  }

  async enterQuarantine(): Promise<void> {
    this.errorMessage.set(null);
    try {
      await firstValueFrom(
        this.http.post<void>(
          this.runtimeConfig.url('/admin/quarantine/enter'),
          { reason: this.quarantineReasonInput.trim() || 'Manual operator quarantine' },
          { withCredentials: true },
        ),
      );
      this.companyContext.setSystemStatus(
        'QUARANTINED',
        this.quarantineReasonInput.trim() || 'Manual operator quarantine',
      );
      this.statusMessage.set('Installation entered QUARANTINE mode.');
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Failed to enter quarantine.');
    }
  }

  async recoverFromQuarantine(): Promise<void> {
    this.errorMessage.set(null);
    const receipt = this.quarantineReasonInput.trim();
    try {
      await firstValueFrom(
        this.http.post<void>(
          this.runtimeConfig.url('/admin/quarantine/recover'),
          { recoveryReceipt: receipt, recoveryReceiptCode: receipt },
          { withCredentials: true },
        ),
      );
      this.companyContext.setSystemStatus('ACTIVE', null);
      this.statusMessage.set('Installation recovered to ACTIVE writer mode.');
    } catch (err: unknown) {
      this.errorMessage.set(err instanceof Error ? err.message : 'Quarantine recovery failed.');
    }
  }
}
