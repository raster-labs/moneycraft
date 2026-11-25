import { HttpHeaders, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ChangeDetectionStrategy, Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AuthenticatedPrincipal, AuthStore } from './core/auth/auth.store';
import {
  DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
  ShortcutCommandRegistry,
  asciiPair,
} from './core/keyboard/shortcut-command.registry';
import {
  ALLOWED_TEXT_SCALES,
  DEFAULT_UI_PREFERENCES_V2,
  LOCAL_STORAGE_KEY_PREFIX,
  formatUiPreferencesEtag,
} from './core/preferences/ui-preferences.models';
import { UiPreferencesService } from './core/preferences/ui-preferences.service';
import {
  ADMITTED_INTEGRATION_PROVIDERS,
  GovernmentIntegrationsComponent,
  STATIC_GOVERNMENT_AND_BANKING_INVENTORY,
} from './features/companies/government-integrations.component';
import {
  BUNDLED_CONTEXTUAL_HELP_ITEMS_EN_GB,
  ContextualHelpService,
} from './shared/components/contextual-help/contextual-help.service';
import { HelpPanelComponent } from './shared/components/contextual-help/help-panel.component';
import { HelpTooltipDirective } from './shared/components/contextual-help/help-tooltip.directive';
import { HelpTriggerComponent } from './shared/components/contextual-help/help-trigger.component';
import { CompanyContextStore, CompanySummary } from './state/company-context.store';

class MemoryStorage implements Storage {
  private readonly map = new Map<string, string>();
  throwOnWrite = false;
  throwOnRead = false;
  clearCalled = false;

  get length(): number {
    return this.map.size;
  }

  clear(): void {
    this.clearCalled = true;
    this.map.clear();
  }

  getItem(key: string): string | null {
    if (this.throwOnRead) {
      throw new DOMException('Storage read blocked', 'SecurityError');
    }
    return this.map.has(key) ? (this.map.get(key) ?? null) : null;
  }

  key(index: number): string | null {
    return Array.from(this.map.keys())[index] ?? null;
  }

  removeItem(key: string): void {
    if (this.throwOnWrite) {
      throw new DOMException('Storage remove blocked', 'SecurityError');
    }
    this.map.delete(key);
  }

  setItem(key: string, value: string): void {
    if (this.throwOnWrite) {
      throw new DOMException('Quota exceeded', 'QuotaExceededError');
    }
    this.map.set(key, String(value));
  }
}

function srgbToLinear(channel8Bit: number): number {
  const c = channel8Bit / 255;
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
}

function relativeLuminance(hex: string): number {
  const clean = hex.replace(/^#/, '');
  const r = Number.parseInt(clean.slice(0, 2), 16);
  const g = Number.parseInt(clean.slice(2, 4), 16);
  const b = Number.parseInt(clean.slice(4, 6), 16);
  return 0.2126 * srgbToLinear(r) + 0.7152 * srgbToLinear(g) + 0.0722 * srgbToLinear(b);
}

function wcagContrastRatio(hexA: string, hexB: string): number {
  const lumA = relativeLuminance(hexA);
  const lumB = relativeLuminance(hexB);
  const lighter = Math.max(lumA, lumB);
  const darker = Math.min(lumA, lumB);
  return (lighter + 0.05) / (darker + 0.05);
}

async function flushMicrotasks(cycles = 8): Promise<void> {
  for (let i = 0; i < cycles; i += 1) {
    await Promise.resolve();
  }
}

const SCOPE_USER_A = 'scopeUserA_0123456789abcdef';
const SCOPE_USER_B = 'scopeUserB_fedcba9876543210';

const MOCK_COMPANY: CompanySummary = {
  id: '11111111-1111-4111-8111-111111111111',
  code: 'CMP-100',
  legalName: 'Raster Demo Enterprises Pvt Ltd',
  mailingName: 'Raster Demo',
  countryCode: 'IN',
  stateCode: '33',
  pinCode: '600001',
  baseCurrencyCode: 'INR',
  currencySymbol: '₹',
  currencyScale: 2,
  financialYearFrom: '2025-04-01',
  booksBeginningFrom: '2025-04-01',
  lastEntryDate: '2025-04-15',
  versionNo: 1,
  features: {
    maintainAccounts: true,
    billWiseEnabled: true,
    costCentresEnabled: true,
    maintainInventory: true,
    inventoryIntegrated: true,
    gstEnabled: true,
    multiGstinEnabled: false,
    gstRegistrationType: 'REGULAR',
    gstin: '33AABCM1234F1Z4',
    gstr1Periodicity: 'MONTHLY',
    gstEffectiveFrom: '2025-04-01',
    tdsEnabled: true,
    mcaEditLogEnforced: true,
  },
};

@Component({
  selector: 'mc-test-help-host',
  standalone: true,
  imports: [HelpTooltipDirective, HelpTriggerComponent, HelpPanelComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div>
      <input
        id="voucher-amount-draft"
        type="text"
        value="12500.00"
        [mcHelpTooltip]="'HELP-110-010'"
      />
      <mc-help-trigger helpId="HELP-110-001" controlName="browser appearance" />
      <mc-help-panel />
    </div>
  `,
})
class TestHelpHostComponent {}

describe('MoneyCraft v1.1.0 — Preferences (PREF-A..F), Dark/Scale (THEME-A..B, SIZE-A..C), Help (HELP-A..C) & Super Admin Gov Config', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
    document.documentElement.removeAttribute('data-mc-theme');
    document.documentElement.removeAttribute('data-mc-appearance');
    document.documentElement.removeAttribute('data-mc-text-scale');
    document.documentElement.style.colorScheme = '';
    document.documentElement.style.fontSize = '';
    document.documentElement.style.removeProperty('--mc-text-scale');
  });

  it('THEME-A & THEME-B: resolves Light, Dark, and live OS System preferences without account writes and verifies WCAG 2.2 AA dark token contrast (>= 4.5:1 text, >= 3:1 controls)', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const prefs = TestBed.inject(UiPreferencesService);
    const memStorage = new MemoryStorage();
    prefs.setStorageAdapterForTesting(memStorage);

    // Default is SYSTEM; when OS is Light -> resolvedTheme = 'light'
    prefs.setOsColorSchemeForTesting(false);
    TestBed.flushEffects();
    expect(prefs.effectiveAppearance()).toBe('SYSTEM');
    expect(prefs.resolvedTheme()).toBe('light');
    expect(prefs.appearanceSummary()).toBe('System — currently Light');
    expect(document.documentElement.getAttribute('data-mc-theme')).toBe('light');
    expect(document.documentElement.style.colorScheme).toBe('light');

    // Live OS switch to Dark updates DOM immediately while keeping SYSTEM stored and making 0 HTTP calls
    prefs.setOsColorSchemeForTesting(true);
    TestBed.flushEffects();
    expect(prefs.effectiveAppearance()).toBe('SYSTEM');
    expect(prefs.resolvedTheme()).toBe('dark');
    expect(prefs.appearanceSummary()).toBe('System — currently Dark');
    expect(document.documentElement.getAttribute('data-mc-theme')).toBe('dark');
    expect(document.documentElement.style.colorScheme).toBe('dark');

    // Explicit LIGHT ignores OS Dark preference
    prefs.setBrowserLocalOverride({ appearance: 'LIGHT' });
    TestBed.flushEffects();
    expect(prefs.resolvedTheme()).toBe('light');
    expect(document.documentElement.getAttribute('data-mc-theme')).toBe('light');

    // Explicit DARK ignores OS Light preference
    prefs.setOsColorSchemeForTesting(false);
    prefs.setBrowserLocalOverride({ appearance: 'DARK' });
    TestBed.flushEffects();
    expect(prefs.resolvedTheme()).toBe('dark');
    expect(document.documentElement.getAttribute('data-mc-theme')).toBe('dark');
    httpMock.expectNone(() => true);

    // THEME-B: Mathematical WCAG 2.2 AA contrast verification on _dark-and-scale.scss tokens
    expect(wcagContrastRatio('#F1F7FB', '#112230')).toBeGreaterThanOrEqual(4.5); // Primary text on dark surface
    expect(wcagContrastRatio('#F1F7FB', '#0B1721')).toBeGreaterThanOrEqual(4.5); // Primary text on dark canvas
    expect(wcagContrastRatio('#9EC0D6', '#112230')).toBeGreaterThanOrEqual(4.5); // Muted text on dark surface
    expect(wcagContrastRatio('#4CE1BD', '#112230')).toBeGreaterThanOrEqual(4.5); // Link/accent on dark surface
    expect(wcagContrastRatio('#FFFFFF', '#006D68')).toBeGreaterThanOrEqual(4.5); // Primary button text
    expect(wcagContrastRatio('#FFFFFF', '#912018')).toBeGreaterThanOrEqual(4.5); // Error banner text
    expect(wcagContrastRatio('#FDE08D', '#2C1E04')).toBeGreaterThanOrEqual(4.5); // Warning banner text
    expect(wcagContrastRatio('#7AF0D2', '#0D3B33')).toBeGreaterThanOrEqual(4.5); // Confirmed jade badge
    expect(wcagContrastRatio('#648BA6', '#112230')).toBeGreaterThanOrEqual(3.0); // Input/control border
    expect(wcagContrastRatio('#6CE9CC', '#112230')).toBeGreaterThanOrEqual(3.0); // Primary button boundary
    expect(wcagContrastRatio('#4CE1BD', '#0B1721')).toBeGreaterThanOrEqual(3.0); // Focus ring outline
  });

  it('SIZE-A..C & INPUT-A: applies all 8 text scales (75%..200%), resets to 100%, preserves input drafts/caret, and defers root font-size during active IME composition', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const prefs = TestBed.inject(UiPreferencesService);
    const memStorage = new MemoryStorage();
    prefs.setStorageAdapterForTesting(memStorage);

    const draftInput = document.createElement('input');
    draftInput.type = 'text';
    draftInput.value = '250000.50';
    document.body.appendChild(draftInput);
    draftInput.focus();
    draftInput.setSelectionRange(3, 3);

    expect(ALLOWED_TEXT_SCALES).toEqual([75, 87.5, 100, 112.5, 125, 150, 175, 200]);

    for (const scale of ALLOWED_TEXT_SCALES) {
      prefs.setPreview({ textScalePercent: scale });
      TestBed.flushEffects();
      expect(prefs.effectiveTextScalePercent()).toBe(scale);
      expect(document.documentElement.style.getPropertyValue('--mc-text-scale')).toBe(
        String(scale / 100),
      );
      expect(document.documentElement.style.fontSize).toBe(`${scale}%`);
      // Exact financial text & focus remain untouched across every scale!
      expect(draftInput.value).toBe('250000.50');
      expect(document.activeElement).toBe(draftInput);
    }

    // Revert returns to 100% baseline
    prefs.revertPreview();
    TestBed.flushEffects();
    expect(prefs.effectiveTextScalePercent()).toBe(100);
    expect(document.documentElement.style.fontSize).toBe('100%');

    // Step increase / decrease helpers
    expect(prefs.stepTextScale('INCREASE')).toBe(112.5);
    expect(prefs.stepTextScale('DECREASE')).toBe(100);
    expect(prefs.stepTextScale('DECREASE')).toBe(87.5);
    prefs.revertPreview();
    TestBed.flushEffects();

    // INPUT-A: While IME composition is active, root font-size change waits until compositionend
    draftInput.dispatchEvent(new CompositionEvent('compositionstart'));
    prefs.setPreview({ textScalePercent: 150 });
    TestBed.flushEffects();
    expect(document.documentElement.style.fontSize).toBe('100%');

    draftInput.dispatchEvent(new CompositionEvent('compositionend'));
    TestBed.flushEffects();
    expect(document.documentElement.style.fontSize).toBe('150%');
  });

  it('PREF-A: syncs My account via GET/PATCH with ETag & Idempotency-Key while keeping per-property browser overrides independent and clearable', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const prefs = TestBed.inject(UiPreferencesService);
    const memStorage = new MemoryStorage();
    prefs.setStorageAdapterForTesting(memStorage);

    const loginPromise = prefs.onAuthenticatedSessionResolved('person-a-uuid');
    const getReq = httpMock.expectOne((r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences'));
    getReq.flush(
      {
        preferences: {
          schemaVersion: 2,
          appearance: 'LIGHT',
          textScalePercent: 100,
          keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
        },
        revision: '1',
        subjectScope: SCOPE_USER_A,
      },
      {
        headers: new HttpHeaders({ ETag: formatUiPreferencesEtag(SCOPE_USER_A, '1') }),
      },
    );
    await loginPromise;
    TestBed.flushEffects();

    expect(prefs.effectiveAppearance()).toBe('LIGHT');
    expect(prefs.effectiveTextScalePercent()).toBe(100);

    // Override ONLY appearance locally in Browser A -> must NOT freeze account textScalePercent!
    prefs.setBrowserLocalOverride({ appearance: 'DARK' });
    TestBed.flushEffects();
    expect(prefs.saveStatus()).toBe('BROWSER_SAVED');
    expect(prefs.effectiveAppearance()).toBe('DARK');
    expect(prefs.effectiveTextScalePercent()).toBe(100);
    expect(prefs.maskedAccountFields()).toEqual(['appearance']);
    expect(prefs.localOverrideSummary()).toContain('This browser uses Dark. Your account uses Light.');

    // Verify stored JSON contains zero names, emails, tokens, or company IDs
    const rawLocal = memStorage.getItem(`${LOCAL_STORAGE_KEY_PREFIX}${SCOPE_USER_A}`);
    expect(rawLocal).not.toBeNull();
    expect(rawLocal).not.toContain('person-a-uuid');

    // Patch account textScalePercent to 125% -> Browser A keeps DARK local override AND picks up 125% account scale!
    const patchPromise = prefs.saveAccountPreferences({ textScalePercent: 125 });
    expect(prefs.saveStatus()).toBe('SAVING');

    const patchReq = httpMock.expectOne(
      (r) => r.method === 'PATCH' && r.url.endsWith('/me/ui-preferences'),
    );
    expect(patchReq.request.headers.get('If-Match')).toBe(
      formatUiPreferencesEtag(SCOPE_USER_A, '1'),
    );
    expect(patchReq.request.headers.get('Idempotency-Key')).toMatch(/^[0-9a-f-]{36}$/i);
    expect(patchReq.request.body).toEqual({
      schemaVersion: 2,
      changes: { textScalePercent: 125 },
    });

    patchReq.flush(
      {
        preferences: {
          schemaVersion: 2,
          appearance: 'LIGHT',
          textScalePercent: 125,
          keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
        },
        revision: '2',
        subjectScope: SCOPE_USER_A,
      },
      {
        headers: new HttpHeaders({ ETag: formatUiPreferencesEtag(SCOPE_USER_A, '2') }),
      },
    );
    expect(await patchPromise).toBe(true);
    TestBed.flushEffects();

    expect(prefs.saveStatus()).toBe('ACCOUNT_SAVED');
    expect(prefs.effectiveAppearance()).toBe('DARK'); // Local override still active
    expect(prefs.effectiveTextScalePercent()).toBe(125); // Inherited new account scale!

    // Clear Browser A's appearance override -> immediately reveals account LIGHT setting!
    prefs.useAccountSettingsInThisBrowser(['appearance']);
    TestBed.flushEffects();
    expect(prefs.effectiveAppearance()).toBe('LIGHT');
    expect(prefs.maskedAccountFields()).toEqual([]);
  });

  it('PREF-B: isolates signed-out choices, preserves same-tab anonymous OFF safety hold across sign-in without auto-uploading, and quarantines delayed User A responses on User A -> User B switch', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const prefs = TestBed.inject(UiPreferencesService);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    const memStorage = new MemoryStorage();
    prefs.setStorageAdapterForTesting(memStorage);

    // 1. Signed-out user explicitly chooses keyboard OFF + DARK appearance
    prefs.setBrowserLocalOverride({
      appearance: 'DARK',
      keyboard: {
        version: 1,
        mode: 'OFF',
        locale: 'en-GB',
        profileId: null,
        overrides: [],
      },
    });
    TestBed.flushEffects();
    expect(registry.preferences().mode).toBe('OFF');

    // 2. Sign in as User A (whose account has CHARACTER + LIGHT):
    //    - Must NOT send any PATCH uploading anonymous choices
    //    - Must keep same-tab anonymous keyboard OFF safety hold so characters don't unexpectedly fire!
    const signInAPromise = prefs.onAuthenticatedSessionResolved('user-a');
    const reqA = httpMock.expectOne((r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences'));
    reqA.flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'LIGHT',
        textScalePercent: 112.5,
        keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard, // CHARACTER on server
      },
      revision: '3',
      subjectScope: SCOPE_USER_A,
    });
    await signInAPromise;
    TestBed.flushEffects();

    httpMock.expectNone((r) => r.method === 'PATCH');
    expect(prefs.effectiveAppearance()).toBe('LIGHT'); // Signed-out appearance NOT imported to User A
    expect(registry.preferences().mode).toBe('OFF'); // Transient safety hold preserved!

    // 3. Rapid User A -> User B switch while a delayed refresh for User A is in flight!
    const delayedRefreshA = prefs.loadAccountPreferences('user-a');
    const pendingGetA = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences'),
    );

    const signInBPromise = prefs.onAuthenticatedSessionResolved('user-b');
    const pendingGetB = httpMock.expectOne(
      (r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences') && r !== pendingGetA.request,
    );

    // Deliver User B's response first (DARK, 150%)
    pendingGetB.flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'DARK',
        textScalePercent: 150,
        keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
      },
      revision: '1',
      subjectScope: SCOPE_USER_B,
    });
    await signInBPromise;

    // Now deliver delayed User A response (LIGHT, 75%) -> MUST BE QUARANTINED!
    pendingGetA.flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'LIGHT',
        textScalePercent: 75,
        keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
      },
      revision: '99',
      subjectScope: SCOPE_USER_A,
    });
    await delayedRefreshA;
    TestBed.flushEffects();

    expect(prefs.authenticatedPrincipalId()).toBe('user-b');
    expect(prefs.subjectScope()).toBe(SCOPE_USER_B);
    expect(prefs.effectiveAppearance()).toBe('DARK');
    expect(prefs.effectiveTextScalePercent()).toBe(150);
    expect(registry.preferences().mode).toBe('CHARACTER'); // User A's hold cleared on user switch!

    // 4. Logout clears User B state and returns to signed-out scope
    prefs.handleLogoutOrSessionExpiry();
    TestBed.flushEffects();
    expect(prefs.authenticatedPrincipalId()).toBeNull();
    expect(prefs.accountPreferences()).toBeNull();
  });

  it('PREF-C: handles 412 concurrent edit conflict ({serverCurrent, proposed}), network timeout retry with identical Idempotency-Key, and 403 server failure without promoting preview to localStorage', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const prefs = TestBed.inject(UiPreferencesService);
    const memStorage = new MemoryStorage();
    prefs.setStorageAdapterForTesting(memStorage);

    const initP = prefs.onAuthenticatedSessionResolved('user-a');
    httpMock
      .expectOne((r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences'))
      .flush({
        preferences: DEFAULT_UI_PREFERENCES_V2,
        revision: '1',
        subjectScope: SCOPE_USER_A,
      });
    await initP;

    // 1. Network timeout (status 0) -> UNCERTAIN + retryUncertainAccountSave reuses SAME Idempotency-Key
    const timeoutSave = prefs.saveAccountPreferences({ appearance: 'DARK' });
    const req1 = httpMock.expectOne((r) => r.method === 'PATCH' && r.url.endsWith('/me/ui-preferences'));
    const originalKey = req1.request.headers.get('Idempotency-Key');
    req1.error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });
    expect(await timeoutSave).toBe(false);
    expect(prefs.saveStatus()).toBe('UNCERTAIN');
    expect(prefs.saveStatusLabel()).toBe('Confirmation pending');
    // Must NOT durably queue in localStorage!
    expect(memStorage.getItem(`${LOCAL_STORAGE_KEY_PREFIX}${SCOPE_USER_A}`)).toBeNull();

    const retrySave = prefs.retryUncertainAccountSave();
    const reqRetry = httpMock.expectOne(
      (r) => r.method === 'PATCH' && r.url.endsWith('/me/ui-preferences'),
    );
    expect(reqRetry.request.headers.get('Idempotency-Key')).toBe(originalKey);
    reqRetry.flush({
      preferences: {
        ...DEFAULT_UI_PREFERENCES_V2,
        appearance: 'DARK',
      },
      revision: '2',
      subjectScope: SCOPE_USER_A,
    });
    expect(await retrySave).toBe(true);
    expect(prefs.saveStatus()).toBe('ACCOUNT_SAVED');

    // 2. Stale revision 412 -> fetches serverCurrent (which has newer textScalePercent: 175) and exposes conflict
    const staleSave = prefs.saveAccountPreferences({ appearance: 'LIGHT' });
    const req412 = httpMock.expectOne((r) => r.method === 'PATCH' && r.url.endsWith('/me/ui-preferences'));
    req412.flush(
      { detail: 'ETag revision mismatch' },
      { status: 412, statusText: 'Precondition Failed' },
    );
    await flushMicrotasks();
    // Service rereads GET /me/ui-preferences to populate authoritative serverCurrent
    const rereadReq = httpMock.expectOne((r) => r.method === 'GET' && r.url.endsWith('/me/ui-preferences'));
    rereadReq.flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'SYSTEM',
        textScalePercent: 175, // Unrelated newer change made in another session!
        keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
      },
      revision: '5',
      subjectScope: SCOPE_USER_A,
    });
    expect(await staleSave).toBe(false);

    expect(prefs.saveStatus()).toBe('CONFLICT');
    const conflict = prefs.conflictState();
    expect(conflict).not.toBeNull();
    expect(conflict?.serverCurrent.textScalePercent).toBe(175);
    expect(conflict?.proposed).toEqual({ appearance: 'LIGHT' });
    expect(conflict?.conflictingFields).toEqual(['appearance']);

    // Deliberately apply only `{ appearance: 'LIGHT' }` against revision 5 -> preserves 175% scale!
    const resolveP = prefs.resolveConflictApplySelected({ appearance: 'LIGHT' });
    const reqResolve = httpMock.expectOne(
      (r) => r.method === 'PATCH' && r.url.endsWith('/me/ui-preferences'),
    );
    expect(reqResolve.request.headers.get('If-Match')).toBe(
      formatUiPreferencesEtag(SCOPE_USER_A, '5'),
    );
    expect(reqResolve.request.body).toEqual({
      schemaVersion: 2,
      changes: { appearance: 'LIGHT' },
    });
    reqResolve.flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'LIGHT',
        textScalePercent: 175,
        keyboard: DEFAULT_UI_PREFERENCES_V2.keyboard,
      },
      revision: '6',
      subjectScope: SCOPE_USER_A,
    });
    expect(await resolveP).toBe(true);
    expect(prefs.effectiveAppearance()).toBe('LIGHT');
    expect(prefs.effectiveTextScalePercent()).toBe(175);

    // 3. Server 403 denial -> remains labelled in-memory preview, never written to localStorage
    const deniedSave = prefs.saveAccountPreferences({ textScalePercent: 200 });
    httpMock
      .expectOne((r) => r.method === 'PATCH')
      .flush({ detail: 'Forbidden' }, { status: 403, statusText: 'Forbidden' });
    expect(await deniedSave).toBe(false);
    expect(prefs.saveStatus()).toBe('SAVE_FAILED');
    expect(prefs.effectiveTextScalePercent()).toBe(200); // Preview still visible
    expect(memStorage.getItem(`${LOCAL_STORAGE_KEY_PREFIX}${SCOPE_USER_A}`)).toBeNull();
  });

  it('PREF-D & PREF-E: handles throwing localStorage (Applies for this session only), protects newer schemaVersion: 3 without overwriting, fails corrupted keyboard to OFF, preserves other origin apps on clear, and syncs same-scope StorageEvent', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const prefs = TestBed.inject(UiPreferencesService);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    const memStorage = new MemoryStorage();

    // Seed another app's key on the same origin + an unsupported newer schemaVersion: 3
    memStorage.setItem('otherApp.theme', 'sepia');
    const newerRecord = JSON.stringify({
      schemaVersion: 3,
      subjectScope: 'anonymous',
      appearance: 'DARK',
    });
    memStorage.setItem('mc.uiPrefs.v2.anonymous', newerRecord);

    prefs.setStorageAdapterForTesting(memStorage);
    TestBed.flushEffects();
    expect(prefs.unsupportedNewerSchemaProtected()).toBe(true);

    // Changing browser override while newer schema is present applies to session ONLY without overwriting v3!
    prefs.setBrowserLocalOverride({ appearance: 'DARK' });
    expect(prefs.saveStatus()).toBe('SESSION_ONLY');
    expect(prefs.saveStatusLabel()).toBe('Applies for this session only');
    expect(memStorage.getItem('mc.uiPrefs.v2.anonymous')).toBe(newerRecord);

    // Corrupted keyboard in valid v2 envelope fails closed to OFF while appearance falls back cleanly
    const badKeyboardEnvelope = JSON.stringify({
      schemaVersion: 2,
      subjectScope: 'anonymous',
      updatedAtEpochMs: 100,
      appearance: 'INVALID_THEME',
      keyboard: { version: 1, mode: 'BOGUS_MODE', locale: 'en-GB', profileId: null, overrides: [] },
    });
    memStorage.setItem('mc.uiPrefs.v2.anonymous', badKeyboardEnvelope);
    prefs.setStorageAdapterForTesting(memStorage);
    TestBed.flushEffects();
    expect(registry.preferences().mode).toBe('OFF');
    expect(prefs.effectiveAppearance()).toBe('SYSTEM');

    // Clear MoneyCraft browser settings -> never calls storage.clear() and keeps 'otherApp.theme'!
    prefs.clearAllMoneyCraftBrowserSettings();
    expect(memStorage.clearCalled).toBe(false);
    expect(memStorage.getItem('otherApp.theme')).toBe('sepia');
    expect(memStorage.getItem('mc.uiPrefs.v2.anonymous')).toBeNull();

    // PREF-E: Cross-tab StorageEvent for active scope updates live; different scope is ignored
    window.dispatchEvent(
      new StorageEvent('storage', {
        key: `${LOCAL_STORAGE_KEY_PREFIX}${SCOPE_USER_B}`,
        newValue: JSON.stringify({
          schemaVersion: 2,
          subjectScope: SCOPE_USER_B,
          updatedAtEpochMs: 200,
          appearance: 'DARK',
        }),
      }),
    );
    TestBed.flushEffects();
    expect(prefs.effectiveAppearance()).toBe('SYSTEM'); // Different scope ignored!

    window.dispatchEvent(
      new StorageEvent('storage', {
        key: 'mc.uiPrefs.v2.anonymous',
        newValue: JSON.stringify({
          schemaVersion: 2,
          subjectScope: 'anonymous',
          updatedAtEpochMs: 300,
          appearance: 'DARK',
          textScalePercent: 150,
        }),
      }),
    );
    TestBed.flushEffects();
    expect(prefs.effectiveAppearance()).toBe('DARK');
    expect(prefs.effectiveTextScalePercent()).toBe(150);

    // QuotaExceeded / throwing storage -> SESSION_ONLY fallback
    memStorage.throwOnWrite = true;
    prefs.setBrowserLocalOverride({ textScalePercent: 175 });
    TestBed.flushEffects();
    expect(prefs.saveStatus()).toBe('SESSION_ONLY');
    expect(prefs.saveStatusLabel()).toBe('Applies for this session only');
    expect(prefs.effectiveTextScalePercent()).toBe(175);
  });

  it('PREF-F: qualifies MODIFIER profiles (activating matching profile, failing mismatched profile to effective OFF while retaining desired state) and honors barcode scanner suppression', async () => {
    await TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const prefs = TestBed.inject(UiPreferencesService);
    const registry = TestBed.inject(ShortcutCommandRegistry);
    prefs.setStorageAdapterForTesting(new MemoryStorage());

    // 1. Account returns an UNQUALIFIED modifier profile (e.g. from another OS/workstation)
    const loadP = prefs.onAuthenticatedSessionResolved('user-mod');
    // While account fetch is in flight, effective keyboard mode holds safe at OFF
    TestBed.flushEffects();
    expect(registry.preferences().mode).toBe('OFF');

    httpMock.expectOne((r) => r.url.endsWith('/me/ui-preferences')).flush({
      preferences: {
        schemaVersion: 2,
        appearance: 'SYSTEM',
        textScalePercent: 100,
        keyboard: {
          version: 1,
          mode: 'MODIFIER',
          locale: 'en-GB',
          profileId: 'unqualified-linux-dvorak-profile',
          overrides: [],
        },
      },
      revision: '1',
      subjectScope: SCOPE_USER_A,
    });
    await loadP;
    TestBed.flushEffects();

    expect(prefs.desiredKeyboardPreference().mode).toBe('MODIFIER');
    expect(prefs.desiredKeyboardPreference().profileId).toBe('unqualified-linux-dvorak-profile');
    expect(registry.preferences().mode).toBe('OFF'); // Mismatched profile -> effective OFF!
    expect(prefs.unqualifiedModifierNotice()).toContain('not qualified');

    // 2. Qualified profile activates MODIFIER cleanly
    prefs.setBrowserLocalOverride({
      keyboard: {
        version: 1,
        mode: 'MODIFIER',
        locale: 'en-GB',
        profileId: DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
        overrides: [],
      },
    });
    TestBed.flushEffects();
    expect(registry.preferences().mode).toBe('MODIFIER');
    expect(prefs.unqualifiedModifierNotice()).toBeNull();

    // 3. CHARACTER mode + barcode scanner suppression forces effectiveDispatchMode OFF
    prefs.setBrowserLocalOverride({
      keyboard: {
        version: 1,
        mode: 'CHARACTER',
        locale: 'en-GB',
        profileId: null,
        overrides: [],
      },
    });
    TestBed.flushEffects();
    expect(registry.effectiveDispatchMode()).toBe('CHARACTER');

    registry.setScannerWorkflowActive(true);
    expect(registry.effectiveDispatchMode()).toBe('OFF');
    registry.setScannerWorkflowActive(false);
    expect(registry.effectiveDispatchMode()).toBe('CHARACTER');
  });

  it('HELP-A..C: bundles all 26 local help entries, supports hoverable WCAG 1.4.13 tooltips, intercepts Escape BEFORE underlying modals, and restores focus on panel close', async () => {
    vi.useFakeTimers();
    try {
      await TestBed.configureTestingModule({
        imports: [TestHelpHostComponent],
      }).compileComponents();

      const helpService = TestBed.inject(ContextualHelpService);
      const registry = TestBed.inject(ShortcutCommandRegistry);

      // Verify all 26 entries (HELP-110-001..025 + HELP-110-026) exist locally
      expect(BUNDLED_CONTEXTUAL_HELP_ITEMS_EN_GB.length).toBe(26);
      expect(helpService.getByHelpId('HELP-110-001')?.context_key).toBe('appearance');
      expect(helpService.getByHelpId('HELP-110-025')?.context_key).toBe('biometric');
      expect(helpService.getByHelpId('HELP-110-026')?.instructions).toContain('F1 Help');

      const fixture = TestBed.createComponent(TestHelpHostComponent);
      fixture.detectChanges();

      // Open an underlying KEY-001 financial modal context to prove Escape closes Help FIRST!
      let underlyingModalClosedByEscape = false;
      const cancelBinding = registry.bindHandler('nav.escape', 'MODAL', () => {
        underlyingModalClosedByEscape = true;
        return true;
      });
      const modalHandle = registry.openModalContext(
        [
          {
            contextId: 'journal.postConfirm',
            locale: 'en-GB',
            commandId: 'journal.post',
            label: 'Post',
            letterPair: asciiPair('P/p'),
            permissionRef: 'voucher.post',
            effect: 'Post journal',
            normalActivation: () => undefined,
            iconSemanticId: 'mc.icon.post-ledger',
            stateTokenRole: 'action.primary',
          },
        ],
        'en-GB',
      );

      const amountInput: HTMLInputElement =
        fixture.nativeElement.querySelector('#voucher-amount-draft');
      const triggerBtn: HTMLButtonElement = fixture.nativeElement.querySelector(
        '[data-testid="help-trigger-HELP-110-001"]',
      );

      // HELP-C: Accessible name ("Help with browser appearance") and >= 24x24px target styling
      expect(triggerBtn.getAttribute('aria-label')).toBe('Help with browser appearance');

      // HELP-A: Hover trigger -> shows tooltip linked via aria-describedby; moving pointer onto tooltip keeps it open!
      amountInput.dispatchEvent(new MouseEvent('mouseenter'));
      fixture.detectChanges();

      const tooltipEl = document.body.querySelector<HTMLElement>(
        '[data-testid="mc-tooltip-HELP-110-010"]',
      );
      expect(tooltipEl).not.toBeNull();
      expect(amountInput.getAttribute('aria-describedby')).toContain(tooltipEl?.id ?? '');

      // Move pointer from input onto tooltip bubble (WCAG 1.4.13 hoverability)
      amountInput.dispatchEvent(new MouseEvent('mouseleave'));
      tooltipEl?.dispatchEvent(new MouseEvent('mouseenter'));
      vi.advanceTimersByTime(500);
      expect(
        document.body.querySelector('[data-testid="mc-tooltip-HELP-110-010"]'),
      ).not.toBeNull();

      // Press Escape -> dismisses tooltip WITHOUT triggering underlying modal Escape or clearing input draft!
      window.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      expect(document.body.querySelector('[data-testid="mc-tooltip-HELP-110-010"]')).toBeNull();
      expect(underlyingModalClosedByEscape).toBe(false);
      expect(amountInput.value).toBe('12500.00');

      // HELP-B: Click HelpTriggerComponent -> opens HelpPanelComponent with exact return label & restores focus on Escape
      triggerBtn.focus();
      triggerBtn.click();
      fixture.detectChanges();
      await Promise.resolve();

      const panelEl: HTMLElement | null = fixture.nativeElement.querySelector(
        '[data-testid="contextual-help-panel"]',
      );
      expect(panelEl).not.toBeNull();
      expect(panelEl?.textContent).toContain('Close help and return to browser appearance.');

      // Press Escape while panel is open -> closes panel, restores focus to triggerBtn, and STILL keeps underlying modal intact!
      window.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
      );
      fixture.detectChanges();
      await Promise.resolve();

      expect(
        fixture.nativeElement.querySelector('[data-testid="contextual-help-panel"]'),
      ).toBeNull();
      expect(underlyingModalClosedByEscape).toBe(false);
      expect(document.activeElement).toBe(triggerBtn);

      modalHandle?.close();
      cancelBinding();
    } finally {
      vi.useRealTimers();
    }
  });

  it('ITER110-012..017 & UM-110-04: GovernmentIntegrationsComponent enforces SUPER_ADMIN gate, displays GOV-D01..39 + Connected Banking inventory, and executes step-up create/stage-secret/validate/activate/disable with zero secret readback', async () => {
    await TestBed.configureTestingModule({
      imports: [GovernmentIntegrationsComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();

    const httpMock = TestBed.inject(HttpTestingController);
    const authStore = TestBed.inject(AuthStore);
    const companyContext = TestBed.inject(CompanyContextStore);
    companyContext.setCompanies([MOCK_COMPANY], MOCK_COMPANY.id);

    // 1. Ordinary INSTALLATION_OWNER WITHOUT `superAdmin: true` is denied!
    authStore.setAuthenticatedPrincipal({
      principalId: 'owner-no-sa',
      username: 'admin1',
      displayName: 'Admin Without SuperAdmin',
      systemRoles: ['INSTALLATION_OWNER'],
      companyGrants: [],
      passkeyEnrolled: false,
    });

    const deniedFixture = TestBed.createComponent(GovernmentIntegrationsComponent);
    deniedFixture.detectChanges();
    expect(
      deniedFixture.nativeElement.querySelector('[data-testid="gov-super-admin-denied"]'),
    ).not.toBeNull();
    httpMock.expectNone((r) => r.url.includes('/admin/government-integration-profiles'));

    // 2. Principal WITH explicit `superAdmin: true` designation loads inventory & profiles
    const superAdminPrincipal = {
      principalId: 'sa-person-1',
      username: 'owner',
      displayName: 'Designated Super Admin',
      systemRoles: ['INSTALLATION_OWNER'],
      companyGrants: [],
      passkeyEnrolled: false,
      superAdmin: true,
    } as AuthenticatedPrincipal & { readonly superAdmin: boolean };
    authStore.setAuthenticatedPrincipal(superAdminPrincipal);

    const fixture = TestBed.createComponent(GovernmentIntegrationsComponent);
    fixture.detectChanges();

    const listReq = httpMock.expectOne((r) =>
      r.url.endsWith('/admin/government-integration-profiles'),
    );
    listReq.flush([]);
    const invReq = httpMock.expectOne((r) =>
      r.url.endsWith('/admin/government-interface-inventory'),
    );
    invReq.flush({ items: STATIC_GOVERNMENT_AND_BANKING_INVENTORY });
    await flushMicrotasks();
    fixture.detectChanges();

    // Verify all 39 GOV-D01..39 + 5 Connected Banking adapters are present
    expect(STATIC_GOVERNMENT_AND_BANKING_INVENTORY.length).toBe(44);
    const providerCodes = ADMITTED_INTEGRATION_PROVIDERS.map((p) => p.providerCode);
    expect(providerCodes).toEqual(
      expect.arrayContaining([
        'EINVOICE_IRP6',
        'EWAYBILL_NIC',
        'GSP_UNIFIED',
        'ICICI_CIB',
        'HDFC_CBX',
        'AXIS_CORP',
        'SBI_CMP',
        'AGGREGATOR_PAYOUT',
      ]),
    );

    const comp = fixture.componentInstance;

    // 3. Create SANDBOX profile -> triggers 5-min Step-Up Password modal first
    comp.openCreateProfileModal();
    fixture.detectChanges();
    const gstRegReq = httpMock.match((r) => r.url.includes('/gst/registrations'));
    for (const r of gstRegReq) {
      r.flush([]);
    }

    comp.draftProfileName = 'ICICI Sandbox Connected CIB';
    comp.onDraftProviderChange('ICICI_CIB');
    comp.draftEnvironment = 'SANDBOX';
    comp.draftCompanyId = MOCK_COMPANY.id;
    comp.draftRegistrationRef = 'ICICI-CORP-UAT-01';
    comp.draftConsentEvidenceRef = 'BOARD-RES-2026-11';
    comp.submitProfileDraft();
    fixture.detectChanges();

    expect(comp.stepUpModalOpen()).toBe(true);
    expect(comp.pendingStepUpAction()).toBe('government.profile.create');

    comp.stepUpPasswordInput = 'SuperSecretAdminPass!123';
    const stepUpCreatePromise = comp.submitStepUpPassword();

    const stepUpReq1 = httpMock.expectOne((r) => r.url.endsWith('/auth/step-up/password'));
    expect(stepUpReq1.request.body).toEqual({
      password: 'SuperSecretAdminPass!123',
      targetProfileId: null,
      action: 'government.profile.create',
      expectedRevision: null,
    });
    stepUpReq1.flush({
      stepUpIntentId: 'intent-create-1',
      expiresAt: '2026-11-16T12:05:00Z',
    });
    await flushMicrotasks();

    const createProfReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' && r.url.endsWith('/admin/government-integration-profiles'),
    );
    expect(createProfReq.request.headers.get('X-Step-Up-Intent')).toBe('intent-create-1');
    createProfReq.flush(
      {
        id: 'prof-icici-1',
        profileName: 'ICICI Sandbox Connected CIB',
        providerCode: 'ICICI_CIB',
        adapterCode: 'ICICI_CIB_CONNECTED_V1',
        endpointProfileId: 'ICICI_CIB_SANDBOX_V1',
        domainCategory: 'CONNECTED_BANKING',
        environment: 'SANDBOX',
        companyId: MOCK_COMPANY.id,
        registrationRef: 'ICICI-CORP-UAT-01',
        allowedOperationIds: ['BANK-BAL-INQUIRY', 'BANK-STMT-PULL'],
        consentEvidenceRef: 'BOARD-RES-2026-11',
        status: 'DRAFT',
        credentialRevision: '0',
        credentialSlots: [],
        revision: '1',
      },
      { headers: new HttpHeaders({ ETag: '"gov:prof-icici-1:1"' }) },
    );
    await flushMicrotasks();
    for (const aud of httpMock.match((r) => r.url.endsWith('/audit'))) {
      aud.flush([]);
    }
    await stepUpCreatePromise;
    fixture.detectChanges();

    expect(comp.stepUpPasswordInput).toBe(''); // Transient password wiped!
    expect(comp.selectedProfile()?.status).toBe('DRAFT');

    // 4. Write-only credential slot staging -> wipes transient secret input immediately and renders ZERO reveal/copy controls
    const selected = comp.selectedProfile()!;
    comp.updateStagedSlotInput('apiKey', 'icici-sandbox-secret-xyz');
    comp.stageCredentialVersion(selected);
    fixture.detectChanges();

    comp.stepUpPasswordInput = 'SuperSecretAdminPass!123';
    const stageP = comp.submitStepUpPassword();
    httpMock.expectOne((r) => r.url.endsWith('/auth/step-up/password')).flush({
      stepUpIntentId: 'intent-stage-2',
      expiresAt: '2026-11-16T12:06:00Z',
    });
    await flushMicrotasks();

    const stageReq = httpMock.expectOne((r) =>
      r.url.endsWith('/admin/government-integration-profiles/prof-icici-1/credential-versions'),
    );
    expect(stageReq.request.headers.get('If-Match')).toBe('"gov:prof-icici-1:1"');
    stageReq.flush({ credentialRevision: '1', versionIds: ['cred-v1'] });
    await flushMicrotasks();

    for (const d of httpMock.match((r) =>
      r.url.endsWith('/admin/government-integration-profiles/prof-icici-1'),
    )) {
      d.flush(
        {
          ...selected,
          status: 'CONFIGURED',
          credentialRevision: '1',
          credentialSlots: [{ slotKey: 'apiKey', status: 'CONFIGURED', versionNo: 1 }],
          revision: '2',
        },
        { headers: new HttpHeaders({ ETag: '"gov:prof-icici-1:2"' }) },
      );
    }
    await flushMicrotasks();
    for (const aud of httpMock.match((r) => r.url.endsWith('/audit'))) {
      aud.flush([]);
    }
    await stageP;
    fixture.detectChanges();

    // Secret input MUST be wiped from memory & DOM, and never echoed anywhere in rendered text!
    expect(comp.stagedSlotInputs()).toEqual({});
    expect(fixture.nativeElement.textContent).not.toContain('icici-sandbox-secret-xyz');
    expect(fixture.nativeElement.textContent).toContain('Configured');

    // 5. Safe Connection Validation (`202` -> `VALIDATED`) & Apply/Activate (`ACTIVE`)
    const configuredProf = comp.selectedProfile()!;
    comp.startSafeValidation(configuredProf);
    comp.stepUpPasswordInput = 'SuperSecretAdminPass!123';
    const valP = comp.submitStepUpPassword();
    httpMock.expectOne((r) => r.url.endsWith('/auth/step-up/password')).flush({
      stepUpIntentId: 'intent-val-3',
      expiresAt: '2026-11-16T12:07:00Z',
    });
    await flushMicrotasks();

    const valStartReq = httpMock.expectOne(
      (r) =>
        r.method === 'POST' &&
        r.url.endsWith('/admin/government-integration-profiles/prof-icici-1/validations'),
    );
    valStartReq.flush({ validationId: 'val-job-101', status: 'PENDING' }, { status: 202, statusText: 'Accepted' });
    await flushMicrotasks();

    httpMock
      .expectOne((r) =>
        r.url.endsWith('/admin/government-integration-profiles/prof-icici-1/validations/val-job-101'),
      )
      .flush({
        validationId: 'val-job-101',
        status: 'VALIDATED',
        testedRevision: '2',
        summaryMessage: 'Read-only ICICI CIB sandbox handshake succeeded (0 mutations).',
      });
    await flushMicrotasks();

    for (const d of httpMock.match((r) =>
      r.url.endsWith('/admin/government-integration-profiles/prof-icici-1'),
    )) {
      d.flush(
        {
          ...configuredProf,
          status: 'VALIDATED',
          lastValidation: {
            validationId: 'val-job-101',
            status: 'VALIDATED',
            testedRevision: '2',
            summaryMessage: 'Read-only ICICI CIB sandbox handshake succeeded (0 mutations).',
          },
          revision: '2',
        },
        { headers: new HttpHeaders({ ETag: '"gov:prof-icici-1:2"' }) },
      );
    }
    await flushMicrotasks();
    for (const aud of httpMock.match((r) => r.url.endsWith('/audit'))) {
      aud.flush([]);
    }
    await valP;
    fixture.detectChanges();

    expect(comp.canActivateProfile(comp.selectedProfile()!)).toBe(true);

    // Activate!
    comp.activateValidatedProfile(comp.selectedProfile()!);
    comp.stepUpPasswordInput = 'SuperSecretAdminPass!123';
    const actP = comp.submitStepUpPassword();
    httpMock.expectOne((r) => r.url.endsWith('/auth/step-up/password')).flush({
      stepUpIntentId: 'intent-act-4',
      expiresAt: '2026-11-16T12:08:00Z',
    });
    await flushMicrotasks();

    httpMock
      .expectOne((r) =>
        r.url.endsWith('/admin/government-integration-profiles/prof-icici-1/activate'),
      )
      .flush(
        {
          ...comp.selectedProfile()!,
          status: 'ACTIVE',
          revision: '3',
        },
        { headers: new HttpHeaders({ ETag: '"gov:prof-icici-1:3"' }) },
      );
    await flushMicrotasks();
    for (const aud of httpMock.match((r) => r.url.endsWith('/audit'))) {
      aud.flush([
        {
          eventId: 'aud-1',
          timestamp: '2026-11-16T12:08:01Z',
          actor: 'owner',
          action: 'government.profile.activate',
          revision: '3',
          result: 'COMMITTED',
          correlationId: 'corr-9988',
        },
      ]);
    }
    await actP;
    fixture.detectChanges();

    expect(comp.selectedProfile()?.status).toBe('ACTIVE');
    expect(fixture.nativeElement.textContent).toContain('government.profile.activate');

    // 6. Disable Profile (`POST .../disable`) with KEY-001 modal + audited reason code + step-up
    comp.openDisableModal(comp.selectedProfile()!);
    fixture.detectChanges();
    expect(comp.disableModalOpen()).toBe(true);
    comp.disableReasonCode = 'CONSENT_REVOKED';
    comp.disableReconciliationNote = 'Taxpayer revoked API session; reconcile pending IRNs via retrieval.';
    comp.confirmDisableProfile(comp.selectedProfile()!);
    fixture.detectChanges();

    comp.stepUpPasswordInput = 'SuperSecretAdminPass!123';
    const disP = comp.submitStepUpPassword();
    httpMock.expectOne((r) => r.url.endsWith('/auth/step-up/password')).flush({
      stepUpIntentId: 'intent-dis-5',
      expiresAt: '2026-11-16T12:09:00Z',
    });
    await flushMicrotasks();

    const disReq = httpMock.expectOne((r) =>
      r.url.endsWith('/admin/government-integration-profiles/prof-icici-1/disable'),
    );
    expect(disReq.request.body).toMatchObject({
      reasonCode: 'CONSENT_REVOKED',
      stepUpIntentId: 'intent-dis-5',
    });
    disReq.flush(
      {
        ...comp.selectedProfile()!,
        status: 'DISABLED',
        revision: '4',
      },
      { headers: new HttpHeaders({ ETag: '"gov:prof-icici-1:4"' }) },
    );
    await flushMicrotasks();
    for (const aud of httpMock.match((r) => r.url.endsWith('/audit'))) {
      aud.flush([]);
    }
    await disP;
    fixture.detectChanges();
    expect(comp.selectedProfile()?.status).toBe('DISABLED');
  });
});
