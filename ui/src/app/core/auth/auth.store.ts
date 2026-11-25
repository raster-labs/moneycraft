import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../http/runtime-config.service';
import { ShortcutCommandRegistry } from '../keyboard/shortcut-command.registry';
import { UiPreferencesService } from '../preferences/ui-preferences.service';

export type SystemRole = 'INSTALLATION_OWNER' | 'SECURITY_AUDITOR' | 'SYSTEM_OPERATOR';

export type CompanyRole =
  | 'COMPANY_ADMIN'
  | 'AUDITOR_APPROVER'
  | 'SENIOR_ACCOUNTANT'
  | 'DATA_ENTRY_OPERATOR'
  | 'INVENTORY_CLERK'
  | 'READ_ONLY_VIEWER';

export interface CompanyGrantSummary {
  readonly grantId: string;
  readonly companyId: string;
  readonly roleCode: CompanyRole;
  readonly voucherTypeAllowlist: readonly string[];
  readonly backdatedDaysLimit: number | null;
  readonly exportPermitted: boolean;
}

export interface AuthenticatedPrincipal {
  readonly principalId: string;
  readonly username: string;
  readonly displayName: string;
  readonly systemRoles: readonly SystemRole[];
  readonly companyGrants: readonly CompanyGrantSummary[];
  readonly passkeyEnrolled: boolean;
  readonly superAdmin?: boolean;
  readonly csrfToken?: string;
}

export interface LoginCredentials {
  readonly username: string;
  readonly password: string;
}

export interface OidcIssuerInfo {
  readonly id?: string;
  readonly issuerId: string;
  readonly registrationId?: string;
  readonly displayName: string;
  readonly issuerUri?: string;
  readonly clientId?: string;
  readonly pkceRequired?: boolean;
  readonly enabled?: boolean;
  readonly lifecycleProfile?: string | null;
  readonly checkedAt?: string | null;
}

@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly uiPreferences = inject(UiPreferencesService);

  private readonly principalSignal = signal<AuthenticatedPrincipal | null>(null);
  private readonly sessionCheckedSignal = signal<boolean>(false);
  private readonly loadingSignal = signal<boolean>(false);
  private readonly errorSignal = signal<string | null>(null);
  private readonly oidcIssuersSignal = signal<readonly OidcIssuerInfo[]>([]);

  readonly principal = this.principalSignal.asReadonly();
  readonly sessionChecked = this.sessionCheckedSignal.asReadonly();
  readonly loading = this.loadingSignal.asReadonly();
  readonly error = this.errorSignal.asReadonly();
  readonly oidcIssuers = this.oidcIssuersSignal.asReadonly();

  readonly isAuthenticated = computed(() => this.principalSignal() !== null);
  readonly systemRoles = computed(() => this.principalSignal()?.systemRoles ?? []);
  readonly companyGrants = computed(() => this.principalSignal()?.companyGrants ?? []);
  readonly isInstallationOwner = computed(() =>
    this.systemRoles().includes('INSTALLATION_OWNER'),
  );
  readonly isSuperAdmin = computed(() => {
    const p = this.principalSignal();
    if (!p) {
      return false;
    }
    if (p.superAdmin === true) {
      return true;
    }
    return (p.systemRoles as readonly string[]).includes('SUPER_ADMIN');
  });
  readonly canAccessAdminConsole = computed(() => {
    const roles = this.systemRoles();
    return (
      roles.includes('INSTALLATION_OWNER') ||
      roles.includes('SECURITY_AUDITOR') ||
      roles.includes('SYSTEM_OPERATOR')
    );
  });

  setAuthenticatedPrincipal(principal: AuthenticatedPrincipal | null): void {
    this.principalSignal.set(principal);
    this.sessionCheckedSignal.set(true);
    this.errorSignal.set(null);
    this.shortcutRegistry.syncAuthenticatedPrincipal(principal?.principalId ?? null);
    if (principal?.principalId) {
      void this.uiPreferences.onAuthenticatedSessionResolved(principal.principalId);
    } else {
      this.uiPreferences.handleLogoutOrSessionExpiry();
    }
  }

  hasSystemRole(role: SystemRole): boolean {
    return this.systemRoles().includes(role);
  }

  hasCompanyRole(companyId: string, allowedRoles?: readonly CompanyRole[]): boolean {
    if (this.isInstallationOwner()) {
      return true;
    }
    const grants = this.companyGrants().filter((g) => g.companyId === companyId);
    if (grants.length === 0) {
      return false;
    }
    if (!allowedRoles || allowedRoles.length === 0) {
      return true;
    }
    return grants.some((g) => allowedRoles.includes(g.roleCode));
  }

  async refreshSession(): Promise<AuthenticatedPrincipal | null> {
    this.loadingSignal.set(true);
    try {
      const me = await firstValueFrom(
        this.http.get<AuthenticatedPrincipal>(this.runtimeConfig.url('/auth/me'), {
          withCredentials: true,
        }),
      );
      this.principalSignal.set(me);
      this.sessionCheckedSignal.set(true);
      this.errorSignal.set(null);
      this.shortcutRegistry.syncAuthenticatedPrincipal(me?.principalId ?? null);
      void this.uiPreferences.onAuthenticatedSessionResolved(me?.principalId ?? null);
      return me;
    } catch {
      this.principalSignal.set(null);
      this.sessionCheckedSignal.set(true);
      this.shortcutRegistry.syncAuthenticatedPrincipal(null);
      this.uiPreferences.handleLogoutOrSessionExpiry();
      return null;
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async loginWithPassword(credentials: LoginCredentials): Promise<AuthenticatedPrincipal> {
    this.loadingSignal.set(true);
    this.errorSignal.set(null);
    try {
      const principal = await firstValueFrom(
        this.http.post<AuthenticatedPrincipal>(
          this.runtimeConfig.url('/auth/login'),
          credentials,
          { withCredentials: true },
        ),
      );
      this.principalSignal.set(principal);
      this.sessionCheckedSignal.set(true);
      this.shortcutRegistry.syncAuthenticatedPrincipal(principal.principalId);
      void this.uiPreferences.onAuthenticatedSessionResolved(principal.principalId);
      return principal;
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Sign-in failed. Verify credentials.';
      this.errorSignal.set(msg);
      throw err;
    } finally {
      this.loadingSignal.set(false);
    }
  }

  async logout(): Promise<void> {
    this.loadingSignal.set(true);
    try {
      await firstValueFrom(
        this.http.post<void>(this.runtimeConfig.url('/auth/logout'), {}, { withCredentials: true }),
      );
    } catch {
      // Clear local session state regardless of server error
    } finally {
      this.principalSignal.set(null);
      this.shortcutRegistry.syncAuthenticatedPrincipal(null);
      this.uiPreferences.handleLogoutOrSessionExpiry();
      this.loadingSignal.set(false);
    }
  }

  async loadPublicOidcIssuers(): Promise<readonly OidcIssuerInfo[]> {
    try {
      const rawList = await firstValueFrom(
        this.http.get<readonly Record<string, unknown>[]>(
          this.runtimeConfig.url('/auth/oidc/issuers'),
          { withCredentials: true },
        ),
      );
      const mapped: OidcIssuerInfo[] = (Array.isArray(rawList) ? rawList : []).map((raw) => {
        const idVal = String(raw['issuerId'] ?? raw['id'] ?? raw['registrationId'] ?? '');
        return {
          id: raw['id'] ? String(raw['id']) : idVal,
          issuerId: idVal,
          registrationId: raw['registrationId'] ? String(raw['registrationId']) : idVal,
          displayName: String(raw['displayName'] ?? raw['name'] ?? idVal),
          issuerUri: raw['issuerUri'] ? String(raw['issuerUri']) : undefined,
          clientId: raw['clientId'] ? String(raw['clientId']) : undefined,
          pkceRequired: raw['pkceRequired'] !== undefined ? Boolean(raw['pkceRequired']) : true,
          enabled: raw['enabled'] !== undefined ? Boolean(raw['enabled']) : true,
          lifecycleProfile: raw['lifecycleProfile'] ? String(raw['lifecycleProfile']) : null,
          checkedAt: raw['checkedAt'] ? String(raw['checkedAt']) : null,
        };
      });
      this.oidcIssuersSignal.set(mapped);
      return mapped;
    } catch {
      this.oidcIssuersSignal.set([]);
      return [];
    }
  }

  async initiateOidcLogin(issuerId: string): Promise<string> {
    const resp = await firstValueFrom(
      this.http.get<{ authorizationUrl: string }>(
        this.runtimeConfig.url(`/auth/oidc/${encodeURIComponent(issuerId)}/authorize`),
        { withCredentials: true },
      ),
    );
    return resp.authorizationUrl;
  }

  async completeOidcCallback(code: string, state: string): Promise<AuthenticatedPrincipal> {
    this.loadingSignal.set(true);
    try {
      const principal = await firstValueFrom(
        this.http.post<AuthenticatedPrincipal>(
          this.runtimeConfig.url('/auth/oidc/callback'),
          { code, state },
          { withCredentials: true },
        ),
      );
      this.principalSignal.set(principal);
      this.sessionCheckedSignal.set(true);
      this.shortcutRegistry.syncAuthenticatedPrincipal(principal.principalId);
      void this.uiPreferences.onAuthenticatedSessionResolved(principal.principalId);
      return principal;
    } finally {
      this.loadingSignal.set(false);
    }
  }
}
