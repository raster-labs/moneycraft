import { DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RuntimeConfigService } from '../http/runtime-config.service';
import {
  DEFAULT_KEYBOARD_PREFERENCES,
  KeyboardPreferenceState,
  ShortcutCommandRegistry,
} from '../keyboard/shortcut-command.registry';
import {
  ALLOWED_TEXT_SCALES,
  ANONYMOUS_SUBJECT_SCOPE,
  AppearanceMode,
  BrowserAppearanceChoice,
  BrowserKeyboardChoice,
  BrowserLocalPreferencesEnvelopeV2,
  BrowserTextScaleChoice,
  DEFAULT_UI_PREFERENCES_V2,
  LOCAL_STORAGE_KEY_PREFIX,
  MAX_PREFERENCES_JSON_DEPTH,
  MAX_PREFERENCES_PAYLOAD_BYTES,
  MeUiPreferencesResponse,
  PREFERENCE_STATUS_LABELS,
  PatchUiPreferencesChanges,
  PatchUiPreferencesRequest,
  PendingUncertainPreferenceWrite,
  PreferencePropertyKey,
  PreferenceSaveStatus,
  ResolvedTheme,
  TextScalePercent,
  UI_PREFERENCES_COPY,
  UI_PREFERENCES_SCHEMA_VERSION,
  UiPreferencesConflictState,
  UiPreferencesV2,
  areKeyboardPreferencesEqual,
  computeJsonDepth,
  formatAppearanceLabel,
  formatUiPreferencesEtag,
  isValidAppearanceMode,
  isValidRevisionString,
  isValidSubjectScope,
  isValidTextScalePercent,
  measureUtf8Bytes,
  parseAndValidateLocalEnvelope,
  parseUiPreferencesEtag,
  validateClosedKeyboardV1,
  validateClosedUiPreferencesV2,
} from './ui-preferences.models';

function allocateIdempotencyUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < 16; i += 1) {
      bytes[i] = (Date.now() + i * 31) & 0xff;
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * MoneyCraft `v1.1.0` — Account & Scoped Browser Presentation Preferences Service (`Docs 50 & 52`).
 *
 * Enforces:
 * - Independent per-property precedence: Preview -> Transient Anonymous Safety Hold -> Browser Local
 *   (`mc.uiPrefs.v2.<subjectScope>`) -> Confirmed Account (`/api/v1/me/ui-preferences`) -> Default.
 * - Strong `ETag` (`"ui:<subjectScope>:<revision>"`), `If-Match`, `Idempotency-Key`, and explicit `412`
 *   conflict state (`{ serverCurrent, proposed }`) without silent overwrite or durable offline queues.
 * - Monotonic `identityEpoch` quarantining delayed User A responses after logout or User A -> User B switch.
 * - Cross-tab `storage` listener scoped strictly to the active `subjectScope`.
 * - Live DOM application (`data-mc-theme`, `color-scheme`, `--mc-text-scale`, root `font-size`) without
 *   page reload, focus movement, caret jump, IME disruption, or financial draft loss.
 */
@Injectable({ providedIn: 'root' })
export class UiPreferencesService {
  private readonly http = inject(HttpClient);
  private readonly runtimeConfig = inject(RuntimeConfigService);
  private readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private identityEpoch = 1;
  private storageAdapterOverride: Storage | null | undefined = undefined;

  private readonly authenticatedPrincipalIdSignal = signal<string | null>(null);
  private readonly subjectScopeSignal = signal<string>(ANONYMOUS_SUBJECT_SCOPE);

  private readonly accountPreferencesSignal = signal<UiPreferencesV2 | null>(null);
  private readonly accountRevisionSignal = signal<string | null>(null);
  private readonly accountEtagSignal = signal<string | null>(null);
  private readonly accountLoadedSignal = signal<boolean>(false);
  private readonly accountLoadingSignal = signal<boolean>(false);

  private readonly localEnvelopeSignal = signal<BrowserLocalPreferencesEnvelopeV2 | null>(null);
  private readonly sessionOnlyEnvelopeSignal = signal<BrowserLocalPreferencesEnvelopeV2 | null>(
    null,
  );
  private readonly unsupportedNewerSchemaProtectedSignal = signal<boolean>(false);
  private readonly storageAvailableSignal = signal<boolean>(true);
  private readonly sessionOnlyModeSignal = signal<boolean>(false);

  private readonly previewChangesSignal = signal<PatchUiPreferencesChanges>({});
  private readonly transientAnonymousKeyboardHoldSignal = signal<KeyboardPreferenceState | null>(
    null,
  );
  private readonly explicitSignedOutKeyboardChoiceSignal = signal<KeyboardPreferenceState | null>(
    null,
  );

  private readonly saveStatusSignal = signal<PreferenceSaveStatus>('IDLE');
  private readonly statusDetailMessageSignal = signal<string | null>(null);
  private readonly conflictStateSignal = signal<UiPreferencesConflictState | null>(null);
  private readonly pendingUncertainWriteSignal = signal<PendingUncertainPreferenceWrite | null>(
    null,
  );
  private readonly crossTabNoticeSignal = signal<string | null>(null);
  private readonly unqualifiedModifierNoticeSignal = signal<string | null>(null);

  private readonly osPrefersDarkSignal = signal<boolean>(false);
  private readonly isImeComposingSignal = signal<boolean>(false);

  readonly authenticatedPrincipalId = this.authenticatedPrincipalIdSignal.asReadonly();
  readonly subjectScope = this.subjectScopeSignal.asReadonly();
  readonly accountPreferences = this.accountPreferencesSignal.asReadonly();
  readonly accountRevision = this.accountRevisionSignal.asReadonly();
  readonly accountEtag = this.accountEtagSignal.asReadonly();
  readonly accountLoaded = this.accountLoadedSignal.asReadonly();
  readonly accountLoading = this.accountLoadingSignal.asReadonly();

  readonly localEnvelope = this.localEnvelopeSignal.asReadonly();
  readonly storageAvailable = this.storageAvailableSignal.asReadonly();
  readonly sessionOnlyMode = this.sessionOnlyModeSignal.asReadonly();
  readonly unsupportedNewerSchemaProtected =
    this.unsupportedNewerSchemaProtectedSignal.asReadonly();

  readonly previewChanges = this.previewChangesSignal.asReadonly();
  readonly transientAnonymousKeyboardHold =
    this.transientAnonymousKeyboardHoldSignal.asReadonly();
  readonly saveStatus = this.saveStatusSignal.asReadonly();
  readonly statusDetailMessage = this.statusDetailMessageSignal.asReadonly();
  readonly conflictState = this.conflictStateSignal.asReadonly();
  readonly pendingUncertainWrite = this.pendingUncertainWriteSignal.asReadonly();
  readonly crossTabNotice = this.crossTabNoticeSignal.asReadonly();
  readonly unqualifiedModifierNotice = this.unqualifiedModifierNoticeSignal.asReadonly();
  readonly osPrefersDark = this.osPrefersDarkSignal.asReadonly();

  readonly saveStatusLabel = computed<string>(() => PREFERENCE_STATUS_LABELS[this.saveStatusSignal()]);

  readonly currentStorageKey = computed<string>(
    () => `${LOCAL_STORAGE_KEY_PREFIX}${this.subjectScopeSignal()}`,
  );

  private readonly activeLocalRecord = computed<BrowserLocalPreferencesEnvelopeV2 | null>(() =>
    this.sessionOnlyEnvelopeSignal() ?? this.localEnvelopeSignal(),
  );

  readonly browserAppearanceChoice = computed<BrowserAppearanceChoice>(
    () => this.activeLocalRecord()?.appearance ?? 'INHERIT',
  );

  readonly browserTextScaleChoice = computed<BrowserTextScaleChoice>(
    () => this.activeLocalRecord()?.textScalePercent ?? 'INHERIT',
  );

  readonly browserKeyboardChoice = computed<BrowserKeyboardChoice>(
    () => this.activeLocalRecord()?.keyboard ?? 'INHERIT',
  );

  readonly effectiveAppearance = computed<AppearanceMode>(() => {
    const preview = this.previewChangesSignal().appearance;
    if (preview !== undefined) {
      return preview;
    }
    const localVal = this.activeLocalRecord()?.appearance;
    if (localVal !== undefined) {
      return localVal;
    }
    const acctVal = this.accountPreferencesSignal()?.appearance;
    if (acctVal !== undefined) {
      return acctVal;
    }
    return DEFAULT_UI_PREFERENCES_V2.appearance;
  });

  readonly effectiveTextScalePercent = computed<TextScalePercent>(() => {
    const preview = this.previewChangesSignal().textScalePercent;
    if (preview !== undefined) {
      return preview;
    }
    const localVal = this.activeLocalRecord()?.textScalePercent;
    if (localVal !== undefined) {
      return localVal;
    }
    const acctVal = this.accountPreferencesSignal()?.textScalePercent;
    if (acctVal !== undefined) {
      return acctVal;
    }
    return DEFAULT_UI_PREFERENCES_V2.textScalePercent;
  });

  /**
   * Desired keyboard preference before environment profile qualification (`Doc 50 §8`).
   */
  readonly desiredKeyboardPreference = computed<KeyboardPreferenceState>(() => {
    const preview = this.previewChangesSignal().keyboard;
    if (preview !== undefined) {
      return preview;
    }
    const hold = this.transientAnonymousKeyboardHoldSignal();
    if (hold !== null) {
      return hold;
    }
    const localVal = this.activeLocalRecord()?.keyboard;
    if (localVal !== undefined) {
      return localVal;
    }
    // `Doc 50 §8`: While account settings are still loading after sign-in without a same-tab hold,
    // keep effective OFF until resolved so characters never unexpectedly dispatch prematurely.
    if (this.authenticatedPrincipalIdSignal() !== null && this.accountLoadingSignal()) {
      return {
        version: 1,
        mode: 'OFF',
        locale: 'en-GB',
        profileId: null,
        overrides: [],
      };
    }
    const acctVal = this.accountPreferencesSignal()?.keyboard;
    if (acctVal !== undefined) {
      return acctVal;
    }
    return DEFAULT_KEYBOARD_PREFERENCES;
  });

  readonly resolvedTheme = computed<ResolvedTheme>(() => {
    const mode = this.effectiveAppearance();
    if (mode === 'DARK') {
      return 'dark';
    }
    if (mode === 'LIGHT') {
      return 'light';
    }
    return this.osPrefersDarkSignal() ? 'dark' : 'light';
  });

  readonly appearanceSummary = computed<string>(() => {
    const mode = this.effectiveAppearance();
    if (mode === 'SYSTEM') {
      const current = this.resolvedTheme() === 'dark' ? 'Dark' : 'Light';
      return `System — currently ${current}`;
    }
    return formatAppearanceLabel(mode);
  });

  /**
   * Lists properties where an explicit browser-local override currently masks the confirmed account value.
   */
  readonly maskedAccountFields = computed<readonly PreferencePropertyKey[]>(() => {
    const acct = this.accountPreferencesSignal();
    const local = this.activeLocalRecord();
    if (!acct || !local) {
      return [];
    }
    const masked: PreferencePropertyKey[] = [];
    if (local.appearance !== undefined && local.appearance !== acct.appearance) {
      masked.push('appearance');
    }
    if (local.textScalePercent !== undefined && local.textScalePercent !== acct.textScalePercent) {
      masked.push('textScalePercent');
    }
    if (
      local.keyboard !== undefined &&
      !areKeyboardPreferencesEqual(local.keyboard, acct.keyboard)
    ) {
      masked.push('keyboard');
    }
    return masked;
  });

  readonly localOverrideSummary = computed<string | null>(() => {
    const acct = this.accountPreferencesSignal();
    const local = this.activeLocalRecord();
    if (!acct || !local) {
      return null;
    }
    const messages: string[] = [];
    if (local.appearance !== undefined) {
      messages.push(
        `This browser uses ${formatAppearanceLabel(local.appearance)}. Your account uses ${formatAppearanceLabel(acct.appearance)}.`,
      );
    }
    if (local.textScalePercent !== undefined) {
      messages.push(
        `This browser uses ${local.textScalePercent}% text size. Your account uses ${acct.textScalePercent}%.`,
      );
    }
    if (local.keyboard !== undefined) {
      messages.push(
        `This browser uses ${local.keyboard.mode} keyboard mode. Your account uses ${acct.keyboard.mode}.`,
      );
    }
    return messages.length > 0 ? messages.join(' ') : null;
  });

  constructor() {
    this.setupMediaQueryListener();
    this.setupCrossTabStorageListener();
    this.setupImeCompositionListener();

    // Initial signed-out load (`Doc 50 §5`): read only `mc.uiPrefs.v2.anonymous`, never last user's record.
    this.loadScopedLocalEnvelope(ANONYMOUS_SUBJECT_SCOPE);

    // Reactively apply theme + text scale to `documentElement` without reload or focus loss.
    effect(() => {
      const theme = this.resolvedTheme();
      const appearance = this.effectiveAppearance();
      const scalePct = this.effectiveTextScalePercent();
      const composing = this.isImeComposingSignal();
      untracked(() => {
        this.applyPresentationToDom(theme, appearance, scalePct, composing);
      });
    });

    // Reactively qualify & apply effective keyboard preferences to `ShortcutCommandRegistry`.
    effect(() => {
      const desired = this.desiredKeyboardPreference();
      untracked(() => {
        this.syncKeyboardToRegistry(desired);
      });
    });
  }

  /**
   * Test hook allowing specs to inject a custom `Storage` mock (e.g., quota-throwing or denied storage).
   */
  setStorageAdapterForTesting(adapter: Storage | null | undefined): void {
    this.storageAdapterOverride = adapter;
    this.sessionOnlyEnvelopeSignal.set(null);
    this.loadScopedLocalEnvelope(this.subjectScopeSignal());
  }

  /**
   * Deterministic hook for testing environmental `prefers-color-scheme` transitions (`THEME-A`).
   */
  setOsColorSchemeForTesting(prefersDark: boolean): void {
    this.osPrefersDarkSignal.set(prefersDark);
  }

  setSessionOnlyMode(enabled: boolean): void {
    this.sessionOnlyModeSignal.set(enabled);
    if (enabled) {
      const existing = this.localEnvelopeSignal();
      if (existing) {
        this.sessionOnlyEnvelopeSignal.set(existing);
      }
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
    } else {
      this.sessionOnlyEnvelopeSignal.set(null);
      this.loadScopedLocalEnvelope(this.subjectScopeSignal());
    }
  }

  clearTransientAnonymousKeyboardHold(): void {
    this.transientAnonymousKeyboardHoldSignal.set(null);
  }

  /**
   * Lifecycle hook for `AuthStore` login / session check / company switch / logout (`Doc 50 §5`, `PREF-B`).
   */
  async onAuthenticatedSessionResolved(
    principalId: string | null,
  ): Promise<MeUiPreferencesResponse | null> {
    const previousPrincipal = this.authenticatedPrincipalIdSignal();

    if (principalId === null) {
      this.handleLogoutOrSessionExpiry();
      return null;
    }

    if (previousPrincipal === principalId && this.accountLoadedSignal()) {
      // Company switch or redundant session check for the same authenticated person:
      // preserve presentation & drafts without cross-user resets.
      this.shortcutRegistry.syncAuthenticatedPrincipal(principalId);
      return {
        preferences: this.accountPreferencesSignal() ?? DEFAULT_UI_PREFERENCES_V2,
        revision: this.accountRevisionSignal() ?? '0',
        subjectScope: this.subjectScopeSignal(),
      };
    }

    const wasAnonymousTransition = previousPrincipal === null;
    const preservedAnonKeyboard = wasAnonymousTransition
      ? this.explicitSignedOutKeyboardChoiceSignal()
      : null;

    // Advance epoch immediately so any late network response from User A is quarantined (`PREF-B`).
    this.identityEpoch += 1;
    this.authenticatedPrincipalIdSignal.set(principalId);
    this.accountPreferencesSignal.set(null);
    this.accountRevisionSignal.set(null);
    this.accountEtagSignal.set(null);
    this.accountLoadedSignal.set(false);
    this.localEnvelopeSignal.set(null);
    this.sessionOnlyEnvelopeSignal.set(null);
    this.unsupportedNewerSchemaProtectedSignal.set(false);
    this.previewChangesSignal.set({});
    this.conflictStateSignal.set(null);
    this.pendingUncertainWriteSignal.set(null);
    this.crossTabNoticeSignal.set(null);
    this.saveStatusSignal.set('IDLE');
    this.statusDetailMessageSignal.set(null);

    // `Doc 50 §5 & §8`: Preserve explicit same-tab signed-out keyboard choice as a transient safety hold
    // across sign-in without automatically uploading signed-out choices to the account!
    this.transientAnonymousKeyboardHoldSignal.set(preservedAnonKeyboard);
    this.explicitSignedOutKeyboardChoiceSignal.set(null);
    this.shortcutRegistry.syncAuthenticatedPrincipal(principalId);

    return this.loadAccountPreferences(principalId);
  }

  /**
   * Immediately purges authenticated in-memory account state, pending previews, conflicts, and
   * safety holds, quarantining in-flight requests and restoring `mc.uiPrefs.v2.anonymous` (`PREF-B`).
   */
  handleLogoutOrSessionExpiry(): void {
    this.identityEpoch += 1;
    this.authenticatedPrincipalIdSignal.set(null);
    this.subjectScopeSignal.set(ANONYMOUS_SUBJECT_SCOPE);
    this.accountPreferencesSignal.set(null);
    this.accountRevisionSignal.set(null);
    this.accountEtagSignal.set(null);
    this.accountLoadedSignal.set(false);
    this.accountLoadingSignal.set(false);
    this.previewChangesSignal.set({});
    this.transientAnonymousKeyboardHoldSignal.set(null);
    this.explicitSignedOutKeyboardChoiceSignal.set(null);
    this.conflictStateSignal.set(null);
    this.pendingUncertainWriteSignal.set(null);
    this.crossTabNoticeSignal.set(null);
    this.unqualifiedModifierNoticeSignal.set(null);
    this.saveStatusSignal.set('IDLE');
    this.statusDetailMessageSignal.set(null);

    this.shortcutRegistry.syncAuthenticatedPrincipal(null);
    this.loadScopedLocalEnvelope(ANONYMOUS_SUBJECT_SCOPE);
  }

  /**
   * Fetches `GET /api/v1/me/ui-preferences` (`Doc 52 §2`), validates `schemaVersion: 2`, `revision`,
   * `subjectScope`, and `ETag`, then loads that principal's scoped browser overrides.
   */
  async loadAccountPreferences(
    expectedPrincipalId?: string,
  ): Promise<MeUiPreferencesResponse | null> {
    const requestEpoch = this.identityEpoch;
    const targetPrincipal = expectedPrincipalId ?? this.authenticatedPrincipalIdSignal();
    if (!targetPrincipal) {
      return null;
    }

    this.accountLoadingSignal.set(true);
    try {
      const response = await firstValueFrom(
        this.http.get<MeUiPreferencesResponse>(this.runtimeConfig.url('/me/ui-preferences'), {
          observe: 'response',
          withCredentials: true,
        }),
      );

      // Quarantine stale response if user signed out or switched User A -> User B while in flight!
      if (
        requestEpoch !== this.identityEpoch ||
        this.authenticatedPrincipalIdSignal() !== targetPrincipal
      ) {
        return null;
      }

      const body = response.body;
      if (
        !body ||
        !isValidSubjectScope(body.subjectScope) ||
        !isValidRevisionString(body.revision)
      ) {
        this.saveStatusSignal.set('LOAD_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.settingsLoadFailure);
        return null;
      }

      const prefsCheck = validateClosedUiPreferencesV2(body.preferences);
      if (!prefsCheck.valid) {
        this.saveStatusSignal.set('LOAD_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.settingsLoadFailure);
        return null;
      }

      const headerEtag = response.headers.get('ETag');
      const parsedHeaderEtag = parseUiPreferencesEtag(headerEtag);
      if (
        headerEtag &&
        (!parsedHeaderEtag ||
          parsedHeaderEtag.subjectScope !== body.subjectScope ||
          parsedHeaderEtag.revision !== body.revision)
      ) {
        this.saveStatusSignal.set('LOAD_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.settingsLoadFailure);
        return null;
      }

      const canonicalEtag = formatUiPreferencesEtag(body.subjectScope, body.revision);
      this.subjectScopeSignal.set(body.subjectScope);
      this.accountPreferencesSignal.set(prefsCheck.value);
      this.accountRevisionSignal.set(body.revision);
      this.accountEtagSignal.set(canonicalEtag);
      this.accountLoadedSignal.set(true);

      // Load scoped local overrides for this validated `subjectScope` (`mc.uiPrefs.v2.<subjectScope>`).
      this.loadScopedLocalEnvelope(body.subjectScope);

      if (this.saveStatusSignal() === 'LOAD_FAILED') {
        this.saveStatusSignal.set('IDLE');
        this.statusDetailMessageSignal.set(null);
      }

      return {
        preferences: prefsCheck.value,
        revision: body.revision,
        subjectScope: body.subjectScope,
      };
    } catch {
      if (
        requestEpoch !== this.identityEpoch ||
        this.authenticatedPrincipalIdSignal() !== targetPrincipal
      ) {
        return null;
      }
      // `Doc 50 §5`: "State that account settings could not be loaded; do not assert they are absent."
      this.saveStatusSignal.set('LOAD_FAILED');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.settingsLoadFailure);
      return null;
    } finally {
      if (requestEpoch === this.identityEpoch) {
        this.accountLoadingSignal.set(false);
      }
    }
  }

  async refreshAccountPreferencesIfAuthenticated(): Promise<MeUiPreferencesResponse | null> {
    if (!this.authenticatedPrincipalIdSignal()) {
      return null;
    }
    return this.loadAccountPreferences();
  }

  /**
   * Applies an immediate in-memory preview (`Preview` status) without writing to `localStorage` or server.
   */
  setPreview(changes: PatchUiPreferencesChanges): void {
    const merged: PatchUiPreferencesChanges = {
      ...this.previewChangesSignal(),
      ...changes,
    };
    this.previewChangesSignal.set(merged);
    this.saveStatusSignal.set('PREVIEW');
    this.statusDetailMessageSignal.set('Preview active — save to My account or This browser.');
  }

  revertPreview(): void {
    this.previewChangesSignal.set({});
    this.conflictStateSignal.set(null);
    this.pendingUncertainWriteSignal.set(null);
    this.saveStatusSignal.set('IDLE');
    this.statusDetailMessageSignal.set(null);
  }

  stepTextScale(direction: 'INCREASE' | 'DECREASE'): TextScalePercent {
    const current = this.effectiveTextScalePercent();
    const idx = ALLOWED_TEXT_SCALES.indexOf(current);
    const currentIndex = idx >= 0 ? idx : ALLOWED_TEXT_SCALES.indexOf(100);
    const nextIndex =
      direction === 'INCREASE'
        ? Math.min(ALLOWED_TEXT_SCALES.length - 1, currentIndex + 1)
        : Math.max(0, currentIndex - 1);
    const nextScale = ALLOWED_TEXT_SCALES[nextIndex];
    this.setPreview({ textScalePercent: nextScale });
    return nextScale;
  }

  /**
   * Conditional partial update via `PATCH /api/v1/me/ui-preferences` (`Doc 50 §5`, `Doc 52 §2`).
   * Requires `If-Match` from the latest `GET` and an RFC 4122 `Idempotency-Key`.
   * Never silently promotes a failed account save to browser storage or queues durably.
   */
  async saveAccountPreferences(
    changes: PatchUiPreferencesChanges,
    explicitIdempotencyKey?: string,
    explicitIfMatch?: string,
  ): Promise<boolean> {
    const principalId = this.authenticatedPrincipalIdSignal();
    if (!principalId) {
      this.previewChangesSignal.set({ ...this.previewChangesSignal(), ...changes });
      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set(
        'Sign in to save preferences to My account, or save to This browser.',
      );
      return false;
    }

    const sanitizedChanges = this.validatePatchChanges(changes);
    if (!sanitizedChanges.valid) {
      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set(sanitizedChanges.reason);
      return false;
    }

    let ifMatch = explicitIfMatch ?? this.accountEtagSignal();
    if (!ifMatch) {
      const loaded = await this.loadAccountPreferences(principalId);
      if (!loaded) {
        this.previewChangesSignal.set({ ...this.previewChangesSignal(), ...sanitizedChanges.value });
        this.saveStatusSignal.set('SAVE_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.accountSaveFailure);
        return false;
      }
      ifMatch = this.accountEtagSignal();
    }

    if (!ifMatch) {
      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.accountSaveFailure);
      return false;
    }

    const payload: PatchUiPreferencesRequest = {
      schemaVersion: UI_PREFERENCES_SCHEMA_VERSION,
      changes: sanitizedChanges.value,
    };
    const serialized = JSON.stringify(payload);
    if (
      measureUtf8Bytes(serialized) > MAX_PREFERENCES_PAYLOAD_BYTES ||
      computeJsonDepth(payload) > MAX_PREFERENCES_JSON_DEPTH
    ) {
      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set('Preference update exceeds 16 KiB or nesting depth limit.');
      return false;
    }

    const idempotencyKey = explicitIdempotencyKey ?? allocateIdempotencyUuid();
    const requestEpoch = this.identityEpoch;

    // Show immediate Preview -> Saving state while keeping user's proposed values visible.
    this.previewChangesSignal.set({ ...this.previewChangesSignal(), ...sanitizedChanges.value });
    this.saveStatusSignal.set('SAVING');
    this.statusDetailMessageSignal.set('Saving to My account…');
    this.conflictStateSignal.set(null);

    try {
      const headers = new HttpHeaders({
        'If-Match': ifMatch,
        'Idempotency-Key': idempotencyKey,
      });

      const response = await firstValueFrom(
        this.http.patch<MeUiPreferencesResponse>(
          this.runtimeConfig.url('/me/ui-preferences'),
          payload,
          {
            headers,
            observe: 'response',
            withCredentials: true,
          },
        ),
      );

      if (
        requestEpoch !== this.identityEpoch ||
        this.authenticatedPrincipalIdSignal() !== principalId
      ) {
        return false;
      }

      const body = response.body;
      if (
        !body ||
        !isValidSubjectScope(body.subjectScope) ||
        !isValidRevisionString(body.revision)
      ) {
        this.saveStatusSignal.set('SAVE_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.accountSaveFailure);
        return false;
      }

      const prefsCheck = validateClosedUiPreferencesV2(body.preferences);
      if (!prefsCheck.valid) {
        this.saveStatusSignal.set('SAVE_FAILED');
        this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.accountSaveFailure);
        return false;
      }

      const newEtag = formatUiPreferencesEtag(body.subjectScope, body.revision);
      this.subjectScopeSignal.set(body.subjectScope);
      this.accountPreferencesSignal.set(prefsCheck.value);
      this.accountRevisionSignal.set(body.revision);
      this.accountEtagSignal.set(newEtag);
      this.accountLoadedSignal.set(true);
      this.pendingUncertainWriteSignal.set(null);
      this.conflictStateSignal.set(null);

      if (sanitizedChanges.value.keyboard !== undefined) {
        this.transientAnonymousKeyboardHoldSignal.set(null);
      }

      // Clear committed fields from preview (changing account never deletes local overrides!).
      const remainingPreview: PatchUiPreferencesChanges = { ...this.previewChangesSignal() };
      if (sanitizedChanges.value.appearance !== undefined) {
        delete (remainingPreview as { appearance?: AppearanceMode }).appearance;
      }
      if (sanitizedChanges.value.textScalePercent !== undefined) {
        delete (remainingPreview as { textScalePercent?: TextScalePercent }).textScalePercent;
      }
      if (sanitizedChanges.value.keyboard !== undefined) {
        delete (remainingPreview as { keyboard?: KeyboardPreferenceState }).keyboard;
      }
      this.previewChangesSignal.set(remainingPreview);

      this.saveStatusSignal.set('ACCOUNT_SAVED');
      const masked = this.localOverrideSummary();
      this.statusDetailMessageSignal.set(
        masked ? `Account saved. Note: ${masked}` : 'Account saved.',
      );
      return true;
    } catch (err: unknown) {
      if (
        requestEpoch !== this.identityEpoch ||
        this.authenticatedPrincipalIdSignal() !== principalId
      ) {
        return false;
      }

      if (err instanceof HttpErrorResponse) {
        if (err.status === 412) {
          await this.handleAccountConflict412(sanitizedChanges.value, err, principalId, requestEpoch);
          return false;
        }
        if (err.status === 0 || err.status === 504 || err.status === 408) {
          this.pendingUncertainWriteSignal.set({
            changes: sanitizedChanges.value,
            ifMatch,
            idempotencyKey,
            startedAtIso: new Date().toISOString(),
          });
          this.saveStatusSignal.set('UNCERTAIN');
          this.statusDetailMessageSignal.set(
            'Confirmation pending — check status or retry original request before changing again.',
          );
          return false;
        }
      }

      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.accountSaveFailure);
      return false;
    }
  }

  /**
   * Retries a timed-out / uncertain account save with its exact original `Idempotency-Key` and `If-Match`,
   * or confirms server state via `GET` (`Doc 52 §2`).
   */
  async retryUncertainAccountSave(): Promise<boolean> {
    const pending = this.pendingUncertainWriteSignal();
    if (!pending) {
      return false;
    }
    return this.saveAccountPreferences(pending.changes, pending.idempotencyKey, pending.ifMatch);
  }

  /**
   * Reloads confirmed server settings after a `412` conflict, discarding the conflicting preview.
   */
  async resolveConflictReloadServer(): Promise<void> {
    this.previewChangesSignal.set({});
    this.conflictStateSignal.set(null);
    await this.loadAccountPreferences();
    if (this.saveStatusSignal() !== 'LOAD_FAILED') {
      this.saveStatusSignal.set('IDLE');
      this.statusDetailMessageSignal.set('Reloaded latest saved account settings.');
    }
  }

  /**
   * Deliberately applies user-reviewed changes on top of the freshly fetched `serverEtag` using a new
   * `Idempotency-Key` (`Doc 50 §5` & `Doc 52 §2`), preserving any unrelated newer server fields!
   */
  async resolveConflictApplySelected(changes: PatchUiPreferencesChanges): Promise<boolean> {
    const conflict = this.conflictStateSignal();
    const latestEtag = conflict?.serverEtag ?? this.accountEtagSignal();
    if (!latestEtag) {
      return false;
    }
    this.conflictStateSignal.set(null);
    return this.saveAccountPreferences(changes, allocateIdempotencyUuid(), latestEtag);
  }

  /**
   * Updates **This browser** local overrides (`mc.uiPrefs.v2.<subjectScope>` or `mc.uiPrefs.v2.anonymous`).
   * Updating a local override NEVER writes to the account (`Doc 50 §5`).
   */
  setBrowserLocalOverride(updates: {
    readonly appearance?: BrowserAppearanceChoice;
    readonly textScalePercent?: BrowserTextScaleChoice;
    readonly keyboard?: BrowserKeyboardChoice;
  }): boolean {
    const scope = this.subjectScopeSignal();
    const current = this.activeLocalRecord();

    let nextAppearance = current?.appearance;
    if (updates.appearance !== undefined) {
      if (updates.appearance === 'INHERIT') {
        nextAppearance = undefined;
      } else if (isValidAppearanceMode(updates.appearance)) {
        nextAppearance = updates.appearance;
      } else {
        return false;
      }
    }

    let nextScale = current?.textScalePercent;
    if (updates.textScalePercent !== undefined) {
      if (updates.textScalePercent === 'INHERIT') {
        nextScale = undefined;
      } else if (isValidTextScalePercent(updates.textScalePercent)) {
        nextScale = updates.textScalePercent;
      } else {
        return false;
      }
    }

    let nextKeyboard = current?.keyboard;
    if (updates.keyboard !== undefined) {
      if (updates.keyboard === 'INHERIT') {
        nextKeyboard = undefined;
      } else {
        const kbdCheck = validateClosedKeyboardV1(updates.keyboard);
        if (!kbdCheck.valid) {
          return false;
        }
        nextKeyboard = kbdCheck.value;
      }
    }

    // Track explicit signed-out keyboard choice so sign-in on the same tab holds it safely (`Doc 50 §8`).
    if (this.authenticatedPrincipalIdSignal() === null && updates.keyboard !== undefined) {
      this.explicitSignedOutKeyboardChoiceSignal.set(
        updates.keyboard === 'INHERIT' ? null : nextKeyboard ?? null,
      );
    }
    if (updates.keyboard !== undefined) {
      this.transientAnonymousKeyboardHoldSignal.set(null);
    }

    // Clear matching preview fields now that they are committed to browser scope.
    const nextPreview: PatchUiPreferencesChanges = { ...this.previewChangesSignal() };
    if (updates.appearance !== undefined) {
      delete (nextPreview as { appearance?: AppearanceMode }).appearance;
    }
    if (updates.textScalePercent !== undefined) {
      delete (nextPreview as { textScalePercent?: TextScalePercent }).textScalePercent;
    }
    if (updates.keyboard !== undefined) {
      delete (nextPreview as { keyboard?: KeyboardPreferenceState }).keyboard;
    }
    this.previewChangesSignal.set(nextPreview);

    const hasAnyOverride =
      nextAppearance !== undefined || nextScale !== undefined || nextKeyboard !== undefined;

    if (!hasAnyOverride) {
      return this.removeCurrentLocalEnvelope();
    }

    const nextEnvelope: BrowserLocalPreferencesEnvelopeV2 = {
      schemaVersion: UI_PREFERENCES_SCHEMA_VERSION,
      subjectScope: scope,
      updatedAtEpochMs: Date.now(),
      ...(nextAppearance !== undefined ? { appearance: nextAppearance } : {}),
      ...(nextScale !== undefined ? { textScalePercent: nextScale } : {}),
      ...(nextKeyboard !== undefined ? { keyboard: nextKeyboard } : {}),
    };

    return this.persistLocalEnvelope(nextEnvelope);
  }

  /**
   * `Doc 50 §5`: **Use account settings in this browser** — clears only the selected local override(s)
   * without resetting the account or deleting other local overrides.
   */
  useAccountSettingsInThisBrowser(fields?: readonly PreferencePropertyKey[]): boolean {
    const targets: readonly PreferencePropertyKey[] =
      fields && fields.length > 0 ? fields : ['appearance', 'textScalePercent', 'keyboard'];
    return this.setBrowserLocalOverride({
      ...(targets.includes('appearance') ? { appearance: 'INHERIT' } : {}),
      ...(targets.includes('textScalePercent') ? { textScalePercent: 'INHERIT' } : {}),
      ...(targets.includes('keyboard') ? { keyboard: 'INHERIT' } : {}),
    });
  }

  /**
   * `Doc 50 §5`: **Reset account appearance settings** — restores the account's `SYSTEM` / `100%`
   * defaults through the ordinary confirmed `PATCH` write without deleting local overrides.
   */
  async resetAccountAppearanceSettings(): Promise<boolean> {
    return this.saveAccountPreferences({
      appearance: DEFAULT_UI_PREFERENCES_V2.appearance,
      textScalePercent: DEFAULT_UI_PREFERENCES_V2.textScalePercent,
    });
  }

  /**
   * `Doc 50 §5`: **Reset this browser's appearance settings** — removes ONLY MoneyCraft's local
   * appearance and text-size overrides for the current scope (never calls `localStorage.clear()`!).
   */
  resetBrowserAppearanceSettings(): boolean {
    this.unsupportedNewerSchemaProtectedSignal.set(false);
    return this.setBrowserLocalOverride({
      appearance: 'INHERIT',
      textScalePercent: 'INHERIT',
    });
  }

  /**
   * Removes ONLY `mc.uiPrefs.v2.*` records belonging to MoneyCraft (`Doc 50 §5 & §6`).
   * Never calls `localStorage.clear()` so other applications on the same origin remain untouched!
   */
  clearAllMoneyCraftBrowserSettings(): void {
    this.unsupportedNewerSchemaProtectedSignal.set(false);
    this.localEnvelopeSignal.set(null);
    this.sessionOnlyEnvelopeSignal.set(null);

    const storage = this.resolveStorage();
    if (!storage) {
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return;
    }

    try {
      const keysToRemove: string[] = [];
      for (let i = 0; i < storage.length; i += 1) {
        const k = storage.key(i);
        if (k && k.startsWith(LOCAL_STORAGE_KEY_PREFIX)) {
          keysToRemove.push(k);
        }
      }
      for (const k of keysToRemove) {
        storage.removeItem(k);
      }
      this.storageAvailableSignal.set(true);
      this.saveStatusSignal.set('BROWSER_SAVED');
      this.statusDetailMessageSignal.set("Cleared MoneyCraft's browser-local settings.");
    } catch {
      this.storageAvailableSignal.set(false);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
    }
  }

  private async handleAccountConflict412(
    proposed: PatchUiPreferencesChanges,
    err: HttpErrorResponse,
    principalId: string,
    requestEpoch: number,
  ): Promise<void> {
    let latestServerPrefs: UiPreferencesV2 | null = null;
    let latestRev: string | null = null;
    let latestEtag: string | null = null;

    // Check if the 412 response body itself supplied `{ current: { preferences, revision, subjectScope } }`
    if (err.error && typeof err.error === 'object') {
      const errRec = err.error as Record<string, unknown>;
      const candidateCurrent = (errRec['current'] ?? errRec) as Record<string, unknown>;
      if (
        candidateCurrent &&
        isValidSubjectScope(candidateCurrent['subjectScope']) &&
        isValidRevisionString(candidateCurrent['revision'])
      ) {
        const validated = validateClosedUiPreferencesV2(candidateCurrent['preferences']);
        if (validated.valid) {
          latestServerPrefs = validated.value;
          latestRev = candidateCurrent['revision'];
          latestEtag = formatUiPreferencesEtag(
            candidateCurrent['subjectScope'],
            candidateCurrent['revision'],
          );
        }
      }
    }

    // If not embedded in 412 body, reread `GET /api/v1/me/ui-preferences` to show authoritative current values.
    if (!latestServerPrefs || !latestRev || !latestEtag) {
      try {
        const freshResp = await firstValueFrom(
          this.http.get<MeUiPreferencesResponse>(this.runtimeConfig.url('/me/ui-preferences'), {
            observe: 'response',
            withCredentials: true,
          }),
        );
        if (
          requestEpoch !== this.identityEpoch ||
          this.authenticatedPrincipalIdSignal() !== principalId
        ) {
          return;
        }
        const b = freshResp.body;
        if (b && isValidSubjectScope(b.subjectScope) && isValidRevisionString(b.revision)) {
          const validated = validateClosedUiPreferencesV2(b.preferences);
          if (validated.valid) {
            latestServerPrefs = validated.value;
            latestRev = b.revision;
            latestEtag = formatUiPreferencesEtag(b.subjectScope, b.revision);
          }
        }
      } catch {
        // Retain prior known server snapshot if GET also failed
      }
    }

    if (
      requestEpoch !== this.identityEpoch ||
      this.authenticatedPrincipalIdSignal() !== principalId
    ) {
      return;
    }

    const serverCurrent = latestServerPrefs ?? this.accountPreferencesSignal() ?? DEFAULT_UI_PREFERENCES_V2;
    const serverRevision = latestRev ?? this.accountRevisionSignal() ?? '0';
    const serverEtag =
      latestEtag ??
      this.accountEtagSignal() ??
      formatUiPreferencesEtag(this.subjectScopeSignal(), serverRevision);

    this.accountPreferencesSignal.set(serverCurrent);
    this.accountRevisionSignal.set(serverRevision);
    this.accountEtagSignal.set(serverEtag);

    const conflictingFields: PreferencePropertyKey[] = [];
    if (proposed.appearance !== undefined && proposed.appearance !== serverCurrent.appearance) {
      conflictingFields.push('appearance');
    }
    if (
      proposed.textScalePercent !== undefined &&
      proposed.textScalePercent !== serverCurrent.textScalePercent
    ) {
      conflictingFields.push('textScalePercent');
    }
    if (
      proposed.keyboard !== undefined &&
      !areKeyboardPreferencesEqual(proposed.keyboard, serverCurrent.keyboard)
    ) {
      conflictingFields.push('keyboard');
    }

    this.conflictStateSignal.set({
      serverCurrent,
      serverRevision,
      serverEtag,
      proposed,
      conflictingFields,
      detectedAtIso: new Date().toISOString(),
    });
    this.saveStatusSignal.set('CONFLICT');
    this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.conflictNotice);
  }

  private validatePatchChanges(
    changes: PatchUiPreferencesChanges,
  ): { readonly valid: true; readonly value: PatchUiPreferencesChanges } | { readonly valid: false; readonly reason: string } {
    if (!changes || typeof changes !== 'object') {
      return { valid: false, reason: 'Preference changes must be an object.' };
    }

    const allowedKeys = new Set(['appearance', 'textScalePercent', 'keyboard']);
    for (const k of Object.keys(changes as Record<string, unknown>)) {
      if (!allowedKeys.has(k)) {
        return { valid: false, reason: `Unsupported preference property "${k}".` };
      }
    }

    let count = 0;
    const out: {
      appearance?: AppearanceMode;
      textScalePercent?: TextScalePercent;
      keyboard?: KeyboardPreferenceState;
    } = {};

    if (changes.appearance !== undefined) {
      if (!isValidAppearanceMode(changes.appearance)) {
        return { valid: false, reason: 'Appearance must be LIGHT, DARK, or SYSTEM.' };
      }
      out.appearance = changes.appearance;
      count += 1;
    }

    if (changes.textScalePercent !== undefined) {
      if (!isValidTextScalePercent(changes.textScalePercent)) {
        return { valid: false, reason: 'Selected text scale percentage is not supported.' };
      }
      out.textScalePercent = changes.textScalePercent;
      count += 1;
    }

    if (changes.keyboard !== undefined) {
      const kbd = validateClosedKeyboardV1(changes.keyboard);
      if (!kbd.valid) {
        return { valid: false, reason: kbd.reason };
      }
      out.keyboard = kbd.value;
      count += 1;
    }

    if (count === 0) {
      return { valid: false, reason: 'Select at least one preference change before saving.' };
    }

    return { valid: true, value: out };
  }

  private loadScopedLocalEnvelope(scope: string): void {
    this.subjectScopeSignal.set(scope);
    const key = `${LOCAL_STORAGE_KEY_PREFIX}${scope}`;
    const storage = this.resolveStorage();

    if (!storage || this.sessionOnlyModeSignal()) {
      this.localEnvelopeSignal.set(null);
      if (!storage) {
        this.storageAvailableSignal.set(false);
      }
      return;
    }

    let raw: string | null;
    try {
      raw = storage.getItem(key);
      this.storageAvailableSignal.set(true);
    } catch {
      this.storageAvailableSignal.set(false);
      this.localEnvelopeSignal.set(null);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return;
    }

    const validated = parseAndValidateLocalEnvelope(raw, scope);
    if (validated.kind === 'UNSUPPORTED_NEWER_SCHEMA') {
      // Protect newer schema record from accidental overwrite (`Doc 50 §6`)!
      this.unsupportedNewerSchemaProtectedSignal.set(true);
      this.localEnvelopeSignal.set(null);
      this.statusDetailMessageSignal.set(validated.defectMessage);
      return;
    }

    this.unsupportedNewerSchemaProtectedSignal.set(false);
    if (validated.kind === 'VALID' && validated.envelope) {
      this.localEnvelopeSignal.set(validated.envelope);
      if (validated.defectMessage) {
        this.statusDetailMessageSignal.set(validated.defectMessage);
      }
    } else {
      this.localEnvelopeSignal.set(null);
      if (validated.kind === 'CORRUPTED_OR_INVALID' && validated.defectMessage) {
        this.statusDetailMessageSignal.set(validated.defectMessage);
      }
    }
  }

  private persistLocalEnvelope(envelope: BrowserLocalPreferencesEnvelopeV2): boolean {
    // `Doc 50 §6`: Never overwrite an unsupported newer schema silently; keep in session memory instead.
    if (this.unsupportedNewerSchemaProtectedSignal() || this.sessionOnlyModeSignal()) {
      this.sessionOnlyEnvelopeSignal.set(envelope);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(
        this.unsupportedNewerSchemaProtectedSignal()
          ? 'Newer browser schema preserved; this change applies for this session only.'
          : UI_PREFERENCES_COPY.localStorageFailure,
      );
      return false;
    }

    const storage = this.resolveStorage();
    if (!storage) {
      this.storageAvailableSignal.set(false);
      this.sessionOnlyEnvelopeSignal.set(envelope);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return false;
    }

    const key = `${LOCAL_STORAGE_KEY_PREFIX}${envelope.subjectScope}`;
    const serialized = JSON.stringify(envelope);
    if (measureUtf8Bytes(serialized) > MAX_PREFERENCES_PAYLOAD_BYTES) {
      this.saveStatusSignal.set('SAVE_FAILED');
      this.statusDetailMessageSignal.set('Browser override exceeds 16 KiB limit.');
      return false;
    }

    try {
      storage.setItem(key, serialized);
      this.storageAvailableSignal.set(true);
      this.sessionOnlyEnvelopeSignal.set(null);
      this.localEnvelopeSignal.set(envelope);
      this.saveStatusSignal.set('BROWSER_SAVED');
      this.statusDetailMessageSignal.set('Browser saved.');
      return true;
    } catch {
      this.storageAvailableSignal.set(false);
      this.sessionOnlyEnvelopeSignal.set(envelope);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return false;
    }
  }

  private removeCurrentLocalEnvelope(): boolean {
    const scope = this.subjectScopeSignal();
    const key = `${LOCAL_STORAGE_KEY_PREFIX}${scope}`;
    this.localEnvelopeSignal.set(null);
    this.sessionOnlyEnvelopeSignal.set(null);

    if (this.unsupportedNewerSchemaProtectedSignal() || this.sessionOnlyModeSignal()) {
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return false;
    }

    const storage = this.resolveStorage();
    if (!storage) {
      this.storageAvailableSignal.set(false);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return false;
    }

    try {
      storage.removeItem(key);
      this.storageAvailableSignal.set(true);
      this.saveStatusSignal.set('BROWSER_SAVED');
      this.statusDetailMessageSignal.set('Using account settings in this browser.');
      return true;
    } catch {
      this.storageAvailableSignal.set(false);
      this.saveStatusSignal.set('SESSION_ONLY');
      this.statusDetailMessageSignal.set(UI_PREFERENCES_COPY.localStorageFailure);
      return false;
    }
  }

  private resolveStorage(): Storage | null {
    if (this.storageAdapterOverride !== undefined) {
      return this.storageAdapterOverride;
    }
    try {
      const win = this.document.defaultView;
      if (win && win.localStorage) {
        return win.localStorage;
      }
      if (typeof localStorage !== 'undefined') {
        return localStorage;
      }
      return null;
    } catch {
      return null;
    }
  }

  private applyPresentationToDom(
    theme: ResolvedTheme,
    appearance: AppearanceMode,
    scalePct: TextScalePercent,
    isImeComposing: boolean,
  ): void {
    const root = this.document.documentElement;
    if (!root) {
      return;
    }

    // Theme custom attributes & CSS `color-scheme` never disturb IME composition, caret, or drafts.
    root.setAttribute('data-mc-theme', theme);
    root.setAttribute('data-mc-appearance', appearance.toLowerCase());
    root.style.colorScheme = theme;

    // Defer root font-size reflow during active IME composition until `compositionend` (`Doc 50 §5 & §8`).
    if (isImeComposing) {
      return;
    }

    const scaleRatio = scalePct / 100;
    root.setAttribute('data-mc-text-scale', String(scalePct));
    root.style.setProperty('--mc-text-scale', String(scaleRatio));
    root.style.fontSize = `${scalePct}%`;
  }

  private syncKeyboardToRegistry(desired: KeyboardPreferenceState): void {
    if (desired.mode === 'MODIFIER') {
      const qualifiedProfiles = this.shortcutRegistry.listQualifiedModifierProfiles();
      const matched = desired.profileId
        ? qualifiedProfiles.find((p) => p.profileId === desired.profileId)
        : undefined;

      if (!matched) {
        // `Doc 50 §8` (`PREF-F`): "MODIFIER cannot activate until the exact current environment/profile
        // is qualified... A mismatched or changed profile/inventory yields effective OFF pending requalification,
        // retaining the saved desire separately."
        this.unqualifiedModifierNoticeSignal.set(
          `Saved modifier profile "${desired.profileId ?? 'none'}" is not qualified in this browser environment; effective keyboard mode is OFF.`,
        );
        this.shortcutRegistry.applyKeyboardPreferences({
          version: 1,
          mode: 'OFF',
          locale: 'en-GB',
          profileId: null,
          overrides: [],
        });
        return;
      }

      const validation = this.shortcutRegistry.applyKeyboardPreferences(desired);
      if (!validation.valid) {
        this.unqualifiedModifierNoticeSignal.set(
          `Saved modifier map could not be qualified (${validation.defects.join('; ')}); effective keyboard mode is OFF.`,
        );
        this.shortcutRegistry.applyKeyboardPreferences({
          version: 1,
          mode: 'OFF',
          locale: 'en-GB',
          profileId: null,
          overrides: [],
        });
        return;
      }

      this.unqualifiedModifierNoticeSignal.set(null);
      return;
    }

    this.unqualifiedModifierNoticeSignal.set(null);
    const res = this.shortcutRegistry.applyKeyboardPreferences(desired);
    if (!res.valid) {
      this.shortcutRegistry.ingestRawPreferenceState(null);
    }
  }

  private setupMediaQueryListener(): void {
    const win = this.document.defaultView;
    if (!win || typeof win.matchMedia !== 'function') {
      return;
    }

    const mql = win.matchMedia('(prefers-color-scheme: dark)');
    this.osPrefersDarkSignal.set(Boolean(mql.matches));

    const onChange = (event: MediaQueryListEvent): void => {
      // `Doc 50 §4`: Updates live without reload, refocus, or account write!
      this.osPrefersDarkSignal.set(Boolean(event.matches));
    };

    if (typeof mql.addEventListener === 'function') {
      mql.addEventListener('change', onChange);
      this.destroyRef.onDestroy(() => mql.removeEventListener('change', onChange));
    }
  }

  private setupCrossTabStorageListener(): void {
    const win = this.document.defaultView;
    if (!win) {
      return;
    }

    const onStorage = (event: StorageEvent): void => {
      // Ignore storage events for other applications or other `subjectScope`s (`PREF-E`)!
      const expectedKey = this.currentStorageKey();
      if (!event.key || event.key !== expectedKey) {
        return;
      }

      const previousTimestamp = this.localEnvelopeSignal()?.updatedAtEpochMs ?? 0;
      const scope = this.subjectScopeSignal();
      const validated = parseAndValidateLocalEnvelope(event.newValue, scope);

      if (validated.kind === 'UNSUPPORTED_NEWER_SCHEMA') {
        this.unsupportedNewerSchemaProtectedSignal.set(true);
        return;
      }

      if (validated.kind === 'ABSENT') {
        this.localEnvelopeSignal.set(null);
        this.crossTabNoticeSignal.set('Browser settings were cleared in another tab.');
        return;
      }

      if (validated.kind === 'VALID' && validated.envelope) {
        const hasUnsavedPreview = Object.keys(this.previewChangesSignal()).length > 0;
        if (hasUnsavedPreview && validated.envelope.updatedAtEpochMs >= previousTimestamp) {
          this.crossTabNoticeSignal.set(
            'Browser settings changed in another same-user tab while you were previewing changes.',
          );
        } else {
          this.crossTabNoticeSignal.set('Updated from another tab in this browser.');
        }
        this.localEnvelopeSignal.set(validated.envelope);
      }
    };

    win.addEventListener('storage', onStorage);
    this.destroyRef.onDestroy(() => win.removeEventListener('storage', onStorage));
  }

  private setupImeCompositionListener(): void {
    const onStart = (): void => this.isImeComposingSignal.set(true);
    const onEnd = (): void => this.isImeComposingSignal.set(false);
    this.document.addEventListener('compositionstart', onStart, true);
    this.document.addEventListener('compositionend', onEnd, true);
    this.destroyRef.onDestroy(() => {
      this.document.removeEventListener('compositionstart', onStart, true);
      this.document.removeEventListener('compositionend', onEnd, true);
    });
  }
}
