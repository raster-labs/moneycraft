import {
  DEFAULT_KEYBOARD_PREFERENCES,
  KeyboardOverrideEntry,
  KeyboardPreferenceState,
} from '../keyboard/modal-action.contract';

/**
 * MoneyCraft Host Iteration `v1.1.0` — UI Presentation & Accessibility Preferences (`Docs 50 & 52`).
 *
 * Implements closed `schemaVersion: 2` (`ui_preferences.schema.json`), per-property inheritance
 * between **My account** (`/api/v1/me/ui-preferences`) and **This browser** (`mc.uiPrefs.v2.<subjectScope>`),
 * strong `ETag` (`"ui:<subjectScope>:<revision>"`) concurrency, conflict comparison, and WCAG 2.2 AA copy.
 */

export const UI_PREFERENCES_SCHEMA_VERSION = 2 as const;

export const LOCAL_STORAGE_KEY_PREFIX = 'mc.uiPrefs.v2.' as const;
export const ANONYMOUS_SUBJECT_SCOPE = 'anonymous' as const;

export const MAX_PREFERENCES_PAYLOAD_BYTES = 16_384; // 16 KiB (`Doc 52 §2`)
export const MAX_PREFERENCES_JSON_DEPTH = 8;
export const MAX_KEYBOARD_OVERRIDES = 256;

export type AppearanceMode = 'LIGHT' | 'DARK' | 'SYSTEM';
export type ResolvedTheme = 'light' | 'dark';

export type TextScalePercent = 75 | 87.5 | 100 | 112.5 | 125 | 150 | 175 | 200;

export type PreferencePropertyKey = 'appearance' | 'textScalePercent' | 'keyboard';

export type BrowserAppearanceChoice = 'INHERIT' | AppearanceMode;
export type BrowserTextScaleChoice = 'INHERIT' | TextScalePercent;
export type BrowserKeyboardChoice = 'INHERIT' | KeyboardPreferenceState;

export type EffectiveValueSource =
  | 'PREVIEW'
  | 'TRANSIENT_SAFETY_HOLD'
  | 'BROWSER_LOCAL'
  | 'SESSION_ONLY_LOCAL'
  | 'ACCOUNT'
  | 'DEFAULT';

export type PreferenceSaveStatus =
  | 'IDLE'
  | 'PREVIEW'
  | 'SAVING'
  | 'ACCOUNT_SAVED'
  | 'BROWSER_SAVED'
  | 'SESSION_ONLY'
  | 'CONFLICT'
  | 'UNCERTAIN'
  | 'SAVE_FAILED'
  | 'LOAD_FAILED';

export const ALLOWED_APPEARANCE_MODES: readonly AppearanceMode[] = Object.freeze([
  'LIGHT',
  'DARK',
  'SYSTEM',
]);

export const ALLOWED_TEXT_SCALES: readonly TextScalePercent[] = Object.freeze([
  75,
  87.5,
  100,
  112.5,
  125,
  150,
  175,
  200,
]);

export interface UiPreferencesV2 {
  readonly schemaVersion: 2;
  readonly appearance: AppearanceMode;
  readonly textScalePercent: TextScalePercent;
  readonly keyboard: KeyboardPreferenceState;
}

export const DEFAULT_UI_PREFERENCES_V2: UiPreferencesV2 = Object.freeze({
  schemaVersion: 2,
  appearance: 'SYSTEM',
  textScalePercent: 100,
  keyboard: DEFAULT_KEYBOARD_PREFERENCES,
});

/**
 * Closed response shape of `GET /api/v1/me/ui-preferences` and `PATCH /api/v1/me/ui-preferences` (`Doc 52 §2`).
 */
export interface MeUiPreferencesResponse {
  readonly preferences: UiPreferencesV2;
  readonly revision: string;
  readonly subjectScope: string;
}

/**
 * Partial property patch body for `PATCH /api/v1/me/ui-preferences` (`Doc 52 §2`).
 * At least one property must be supplied; `keyboard` replaces the complete `Doc 45` v1 object.
 */
export interface PatchUiPreferencesChanges {
  readonly appearance?: AppearanceMode;
  readonly textScalePercent?: TextScalePercent;
  readonly keyboard?: KeyboardPreferenceState;
}

export interface PatchUiPreferencesRequest {
  readonly schemaVersion: 2;
  readonly changes: PatchUiPreferencesChanges;
}

/**
 * Origin-scoped `localStorage` envelope (`mc.uiPrefs.v2.<subjectScope>` or `mc.uiPrefs.v2.anonymous`).
 * Absent properties inherit the confirmed account value (or default when signed out).
 * Contains zero credentials, tokens, names, emails, company IDs, or financial drafts (`Doc 50 §6`).
 */
export interface BrowserLocalPreferencesEnvelopeV2 {
  readonly schemaVersion: 2;
  readonly subjectScope: string;
  readonly updatedAtEpochMs: number;
  readonly appearance?: AppearanceMode;
  readonly textScalePercent?: TextScalePercent;
  readonly keyboard?: KeyboardPreferenceState;
}

export interface UiPreferencesConflictState {
  readonly serverCurrent: UiPreferencesV2;
  readonly serverRevision: string;
  readonly serverEtag: string;
  readonly proposed: PatchUiPreferencesChanges;
  readonly conflictingFields: readonly PreferencePropertyKey[];
  readonly detectedAtIso: string;
}

export interface PendingUncertainPreferenceWrite {
  readonly changes: PatchUiPreferencesChanges;
  readonly ifMatch: string;
  readonly idempotencyKey: string;
  readonly startedAtIso: string;
}

export interface LocalEnvelopeValidationOutcome {
  readonly kind:
    | 'VALID'
    | 'ABSENT'
    | 'UNSUPPORTED_NEWER_SCHEMA'
    | 'CORRUPTED_OR_INVALID';
  readonly envelope: BrowserLocalPreferencesEnvelopeV2 | null;
  readonly keyboardFailedClosedToOff: boolean;
  readonly defectMessage: string | null;
}

export const PREFERENCE_STATUS_LABELS: Readonly<Record<PreferenceSaveStatus, string>> =
  Object.freeze({
    IDLE: 'Ready',
    PREVIEW: 'Preview',
    SAVING: 'Saving',
    ACCOUNT_SAVED: 'Account saved',
    BROWSER_SAVED: 'Browser saved',
    SESSION_ONLY: 'Applies for this session only',
    CONFLICT: 'Settings changed in another session',
    UNCERTAIN: 'Confirmation pending',
    SAVE_FAILED: 'Account save failed — preview active',
    LOAD_FAILED: 'Account settings could not be loaded',
  });

/**
 * Validated UI instructional copy from `Doc 50 §7`.
 */
export const UI_PREFERENCES_COPY = Object.freeze({
  appearanceChoice:
    'System follows the appearance reported by your browser. Choose Light or Dark to keep a fixed appearance.',
  accountScope: 'Saved to your account and used when you sign in on another browser.',
  localOverride: 'Use a different appearance in this browser. Your account setting stays unchanged.',
  inheritOption: 'Use account setting.',
  textSize: "Changes MoneyCraft's interface text. Your browser's zoom controls continue to work.",
  accountSaveFailure:
    'Your account setting could not be saved. This preview is still visible. Retry or revert.',
  localStorageFailure:
    'Browser settings could not be stored. This change applies for this session only.',
  settingsLoadFailure:
    'Account settings could not be loaded. Your saved settings have not been changed.',
  conflictNotice:
    'These settings changed in another session. Review the saved values before applying your changes.',
});

const SUBJECT_SCOPE_PATTERN = /^[A-Za-z0-9_-]{16,128}$/;
const NONNEGATIVE_REVISION_PATTERN = /^(?:0|[1-9][0-9]*)$/;
const STRONG_UI_ETAG_PATTERN = /^"ui:([A-Za-z0-9_-]{16,128}):(0|[1-9][0-9]*)"$/;

const ALLOWED_TOP_LEVEL_PREFS_KEYS: ReadonlySet<string> = new Set([
  'schemaVersion',
  'appearance',
  'textScalePercent',
  'keyboard',
]);

const ALLOWED_LOCAL_ENVELOPE_KEYS: ReadonlySet<string> = new Set([
  'schemaVersion',
  'subjectScope',
  'updatedAtEpochMs',
  'appearance',
  'textScalePercent',
  'keyboard',
]);

const ALLOWED_KEYBOARD_KEYS: ReadonlySet<string> = new Set([
  'version',
  'mode',
  'locale',
  'profileId',
  'overrides',
]);

const ALLOWED_OVERRIDE_KEYS: ReadonlySet<string> = new Set([
  'contextId',
  'commandId',
  'chordId',
]);

export function isValidAppearanceMode(value: unknown): value is AppearanceMode {
  return value === 'LIGHT' || value === 'DARK' || value === 'SYSTEM';
}

export function isValidTextScalePercent(value: unknown): value is TextScalePercent {
  return (
    typeof value === 'number' &&
    Number.isFinite(value) &&
    (ALLOWED_TEXT_SCALES as readonly number[]).includes(value)
  );
}

export function isValidSubjectScope(value: unknown): value is string {
  return typeof value === 'string' && SUBJECT_SCOPE_PATTERN.test(value);
}

export function isValidRevisionString(value: unknown): value is string {
  return typeof value === 'string' && NONNEGATIVE_REVISION_PATTERN.test(value);
}

export function formatUiPreferencesEtag(subjectScope: string, revision: string): string {
  return `"ui:${subjectScope}:${revision}"`;
}

export function parseUiPreferencesEtag(
  etag: string | null | undefined,
): { readonly subjectScope: string; readonly revision: string } | null {
  if (!etag) {
    return null;
  }
  const trimmed = etag.trim();
  const match = STRONG_UI_ETAG_PATTERN.exec(trimmed);
  if (!match) {
    return null;
  }
  return {
    subjectScope: match[1],
    revision: match[2],
  };
}

export function measureUtf8Bytes(input: string): number {
  if (typeof TextEncoder !== 'undefined') {
    return new TextEncoder().encode(input).byteLength;
  }
  return input.length;
}

export function computeJsonDepth(value: unknown, currentDepth = 1): number {
  if (currentDepth > MAX_PREFERENCES_JSON_DEPTH + 1) {
    return currentDepth;
  }
  if (value === null || typeof value !== 'object') {
    return currentDepth;
  }
  if (Array.isArray(value)) {
    if (value.length === 0) {
      return currentDepth + 1;
    }
    let maxChild = currentDepth + 1;
    for (const item of value) {
      const d = computeJsonDepth(item, currentDepth + 1);
      if (d > maxChild) {
        maxChild = d;
      }
    }
    return maxChild;
  }
  const entries = Object.values(value as Record<string, unknown>);
  if (entries.length === 0) {
    return currentDepth + 1;
  }
  let maxChild = currentDepth + 1;
  for (const entry of entries) {
    const d = computeJsonDepth(entry, currentDepth + 1);
    if (d > maxChild) {
      maxChild = d;
    }
  }
  return maxChild;
}

export function validateClosedKeyboardV1(
  candidate: unknown,
): { readonly valid: true; readonly value: KeyboardPreferenceState } | { readonly valid: false; readonly reason: string } {
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) {
    return { valid: false, reason: 'Keyboard preference must be a closed version 1 object.' };
  }
  const record = candidate as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (!ALLOWED_KEYBOARD_KEYS.has(key)) {
      return { valid: false, reason: `Unexpected field "${key}" in keyboard preference.` };
    }
  }
  if (record['version'] !== 1) {
    return { valid: false, reason: 'Keyboard preference version must equal 1.' };
  }
  const mode = record['mode'];
  if (mode !== 'CHARACTER' && mode !== 'OFF' && mode !== 'MODIFIER') {
    return { valid: false, reason: 'Keyboard mode must be CHARACTER, OFF, or MODIFIER.' };
  }
  if (record['locale'] !== 'en-GB') {
    return { valid: false, reason: 'Keyboard locale must equal en-GB.' };
  }
  const profileId = record['profileId'];
  if (
    profileId !== null &&
    (typeof profileId !== 'string' || profileId.trim().length === 0 || profileId.length > 128)
  ) {
    return { valid: false, reason: 'Keyboard profileId must be null or a 1..128 character string.' };
  }
  const overridesRaw = record['overrides'];
  if (!Array.isArray(overridesRaw) || overridesRaw.length > MAX_KEYBOARD_OVERRIDES) {
    return {
      valid: false,
      reason: `Keyboard overrides must be an array of at most ${MAX_KEYBOARD_OVERRIDES} items.`,
    };
  }

  const overrides: KeyboardOverrideEntry[] = [];
  for (const item of overridesRaw) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) {
      return { valid: false, reason: 'Keyboard override item must be an object.' };
    }
    const itemRec = item as Record<string, unknown>;
    for (const k of Object.keys(itemRec)) {
      if (!ALLOWED_OVERRIDE_KEYS.has(k)) {
        return { valid: false, reason: `Unexpected field "${k}" in keyboard override.` };
      }
    }
    const contextId = itemRec['contextId'];
    const commandId = itemRec['commandId'];
    const chordId = itemRec['chordId'];
    if (
      typeof contextId !== 'string' ||
      contextId.length < 1 ||
      contextId.length > 128 ||
      typeof commandId !== 'string' ||
      commandId.length < 1 ||
      commandId.length > 128 ||
      typeof chordId !== 'string' ||
      chordId.length < 1 ||
      chordId.length > 128
    ) {
      return {
        valid: false,
        reason: 'Keyboard override contextId, commandId, and chordId must be 1..128 character strings.',
      };
    }
    overrides.push({ contextId, commandId, chordId });
  }

  return {
    valid: true,
    value: {
      version: 1,
      mode,
      locale: 'en-GB',
      profileId: profileId ?? null,
      overrides,
    },
  };
}

export function validateClosedUiPreferencesV2(
  candidate: unknown,
): { readonly valid: true; readonly value: UiPreferencesV2 } | { readonly valid: false; readonly reason: string } {
  if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) {
    return { valid: false, reason: 'UI preferences must be a JSON object.' };
  }
  if (computeJsonDepth(candidate) > MAX_PREFERENCES_JSON_DEPTH) {
    return { valid: false, reason: 'UI preferences exceed maximum JSON nesting depth of 8.' };
  }
  const record = candidate as Record<string, unknown>;
  for (const key of Object.keys(record)) {
    if (!ALLOWED_TOP_LEVEL_PREFS_KEYS.has(key)) {
      return { valid: false, reason: `Unknown UI preference property "${key}".` };
    }
  }
  if (record['schemaVersion'] !== UI_PREFERENCES_SCHEMA_VERSION) {
    return { valid: false, reason: 'UI preferences schemaVersion must equal 2.' };
  }
  const appearance = record['appearance'];
  if (!isValidAppearanceMode(appearance)) {
    return { valid: false, reason: 'UI appearance must be LIGHT, DARK, or SYSTEM.' };
  }
  const textScalePercent = record['textScalePercent'];
  if (!isValidTextScalePercent(textScalePercent)) {
    return { valid: false, reason: 'UI textScalePercent is not an admitted scale preset.' };
  }
  const kbd = validateClosedKeyboardV1(record['keyboard']);
  if (!kbd.valid) {
    return kbd;
  }
  return {
    valid: true,
    value: {
      schemaVersion: 2,
      appearance,
      textScalePercent,
      keyboard: kbd.value,
    },
  };
}

export function parseAndValidateLocalEnvelope(
  rawJson: string | null,
  expectedSubjectScope: string,
): LocalEnvelopeValidationOutcome {
  if (rawJson === null || rawJson.trim().length === 0) {
    return {
      kind: 'ABSENT',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: null,
    };
  }

  if (measureUtf8Bytes(rawJson) > MAX_PREFERENCES_PAYLOAD_BYTES) {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preference record exceeds 16 KiB limit; using safe defaults.',
    };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(rawJson);
  } catch {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preference JSON is malformed; using safe defaults.',
    };
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preference record is not an object; using safe defaults.',
    };
  }

  if (computeJsonDepth(parsed) > MAX_PREFERENCES_JSON_DEPTH) {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preference record exceeds nesting depth 8; using safe defaults.',
    };
  }

  const record = parsed as Record<string, unknown>;
  const rawVersion = record['schemaVersion'];

  // `Doc 50 §6`: "Migrate recognised older schemas only; ignore an unsupported newer schema without overwriting it."
  if (typeof rawVersion === 'number' && rawVersion > UI_PREFERENCES_SCHEMA_VERSION) {
    return {
      kind: 'UNSUPPORTED_NEWER_SCHEMA',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: `Stored browser preferences use newer schemaVersion ${String(rawVersion)}; ignored without overwriting.`,
    };
  }

  if (rawVersion !== UI_PREFERENCES_SCHEMA_VERSION) {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preferences have unsupported schemaVersion; using safe defaults.',
    };
  }

  for (const key of Object.keys(record)) {
    if (!ALLOWED_LOCAL_ENVELOPE_KEYS.has(key)) {
      return {
        kind: 'CORRUPTED_OR_INVALID',
        envelope: null,
        keyboardFailedClosedToOff: false,
        defectMessage: `Forbidden or unknown field "${key}" rejected from browser preference store.`,
      };
    }
  }

  if (record['subjectScope'] !== expectedSubjectScope) {
    return {
      kind: 'CORRUPTED_OR_INVALID',
      envelope: null,
      keyboardFailedClosedToOff: false,
      defectMessage: 'Stored browser preference scope does not match active identity scope; ignored.',
    };
  }

  const updatedAtRaw = record['updatedAtEpochMs'];
  const updatedAtEpochMs =
    typeof updatedAtRaw === 'number' && Number.isFinite(updatedAtRaw) && updatedAtRaw >= 0
      ? updatedAtRaw
      : 0;

  let appearance: AppearanceMode | undefined;
  let defectMessage: string | null = null;
  if (record['appearance'] !== undefined) {
    if (isValidAppearanceMode(record['appearance'])) {
      appearance = record['appearance'];
    } else {
      defectMessage = 'Invalid stored browser appearance ignored; falling back to inherited/default.';
    }
  }

  let textScalePercent: TextScalePercent | undefined;
  if (record['textScalePercent'] !== undefined) {
    if (isValidTextScalePercent(record['textScalePercent'])) {
      textScalePercent = record['textScalePercent'];
    } else {
      defectMessage = 'Invalid stored browser text size ignored; falling back to inherited/default.';
    }
  }

  let keyboard: KeyboardPreferenceState | undefined;
  let keyboardFailedClosedToOff = false;
  if (record['keyboard'] !== undefined) {
    const kbdCheck = validateClosedKeyboardV1(record['keyboard']);
    if (kbdCheck.valid) {
      keyboard = kbdCheck.value;
    } else {
      // `Doc 50 §6 & §8`: "Invalid keyboard state fails OFF; invalid appearance/size uses defaults."
      keyboardFailedClosedToOff = true;
      keyboard = {
        version: 1,
        mode: 'OFF',
        locale: 'en-GB',
        profileId: null,
        overrides: [],
      };
      defectMessage = `Invalid stored keyboard preference (${kbdCheck.reason}); failed closed to OFF.`;
    }
  }

  return {
    kind: 'VALID',
    envelope: {
      schemaVersion: 2,
      subjectScope: expectedSubjectScope,
      updatedAtEpochMs,
      ...(appearance !== undefined ? { appearance } : {}),
      ...(textScalePercent !== undefined ? { textScalePercent } : {}),
      ...(keyboard !== undefined ? { keyboard } : {}),
    },
    keyboardFailedClosedToOff,
    defectMessage,
  };
}

export function areKeyboardPreferencesEqual(
  a: KeyboardPreferenceState | undefined,
  b: KeyboardPreferenceState | undefined,
): boolean {
  if (a === b) {
    return true;
  }
  if (!a || !b) {
    return false;
  }
  if (
    a.version !== b.version ||
    a.mode !== b.mode ||
    a.locale !== b.locale ||
    a.profileId !== b.profileId ||
    a.overrides.length !== b.overrides.length
  ) {
    return false;
  }
  for (let i = 0; i < a.overrides.length; i += 1) {
    const oa = a.overrides[i];
    const ob = b.overrides[i];
    if (
      oa.contextId !== ob.contextId ||
      oa.commandId !== ob.commandId ||
      oa.chordId !== ob.chordId
    ) {
      return false;
    }
  }
  return true;
}

export function formatAppearanceLabel(mode: AppearanceMode): string {
  switch (mode) {
    case 'LIGHT':
      return 'Light';
    case 'DARK':
      return 'Dark';
    case 'SYSTEM':
      return 'System';
  }
}
