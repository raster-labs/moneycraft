import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  effect,
  inject,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { AuthStore } from '../../../core/auth/auth.store';
import {
  ActiveModalContextHandle,
  DEFAULT_KEYBOARD_PREFERENCES,
  DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID,
  KeyboardDispatchMode,
  KeyboardOverrideEntry,
  KeyboardPreferenceState,
  ModalActionDefinition,
  QualifiedModifierProfile,
  ShortcutCommandRegistry,
  asciiPair,
} from '../../../core/keyboard/shortcut-command.registry';
import {
  ALLOWED_APPEARANCE_MODES,
  ALLOWED_TEXT_SCALES,
  AppearanceMode,
  DEFAULT_UI_PREFERENCES_V2,
  TextScalePercent,
} from '../../../core/preferences/ui-preferences.models';
import { UiPreferencesService } from '../../../core/preferences/ui-preferences.service';
import { HelpTriggerComponent } from '../contextual-help/help-trigger.component';
import { SemanticIconComponent } from '../semantic-icon/semantic-icon.component';

export type PreferencePersistenceTarget = 'ACCOUNT' | 'BROWSER';

@Component({
  selector: 'mc-keyboard-settings-modal',
  standalone: true,
  imports: [FormsModule, SemanticIconComponent, HelpTriggerComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (shortcutRegistry.settingsModalOpen()) {
      <div
        class="mc-kbd-modal-backdrop"
        role="dialog"
        aria-modal="true"
        aria-labelledby="mc-kbd-settings-title"
        (keydown.escape)="closeWithoutApply($event)"
      >
        <div class="mc-card mc-kbd-modal-card" data-testid="ui-preferences-modal">
          <div class="mc-kbd-modal-header">
            <div>
              <span class="mc-badge mc-badge-slate">KEY-001 &bull; v2</span>
              <strong id="mc-kbd-settings-title">
                Appearance, Text Scale &amp; Keyboard Preferences
              </strong>
              <mc-help-trigger
                helpId="HELP-110-001"
                controlName="Appearance, text scale, and keyboard preferences"
              />
            </div>
            <span class="mc-kbd-scope-note" data-testid="pref-save-status-label">
              {{ uiPrefs.saveStatusLabel() }} &bull; {{ uiPrefs.appearanceSummary() }}
            </span>
          </div>

          <div class="mc-kbd-modal-body">
            @if (shortcutRegistry.preferenceFailureReason(); as failReason) {
              <div class="mc-status-banner mc-status-warning" role="alert">
                <mc-icon name="warning" [size]="20" />
                <span>{{ failReason }}</span>
              </div>
            }

            @if (uiPrefs.statusDetailMessage(); as statusDetail) {
              <div class="mc-status-banner mc-status-pending" role="status">
                <mc-icon name="details" [size]="16" />
                <span>{{ statusDetail }}</span>
              </div>
            }

            @if (uiPrefs.conflictState(); as conflict) {
              <div
                class="mc-status-banner mc-status-warning"
                role="alert"
                data-testid="pref-conflict-banner"
              >
                <mc-icon name="warning" [size]="20" />
                <div class="mc-kbd-banner-col">
                  <strong>
                    Account settings changed in another session (server revision
                    {{ conflict.serverRevision }}).
                  </strong>
                  <div class="mc-kbd-inline-actions">
                    <button
                      type="button"
                      class="mc-btn mc-btn-secondary"
                      data-testid="pref-conflict-reload-btn"
                      (click)="reloadFromConflict()"
                    >
                      Reload server settings
                    </button>
                    <button
                      type="button"
                      class="mc-btn mc-btn-primary"
                      data-testid="pref-conflict-apply-btn"
                      (click)="applyOverConflict()"
                    >
                      Apply my selected settings
                    </button>
                  </div>
                </div>
              </div>
            }

            @if (uiPrefs.pendingUncertainWrite()) {
              <div
                class="mc-status-banner mc-status-unknown"
                role="alert"
                data-testid="pref-uncertain-banner"
              >
                <mc-icon name="unknown" [size]="20" />
                <div class="mc-kbd-banner-col">
                  <span>Account save outcome was uncertain. Retry with original Idempotency-Key.</span>
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="pref-uncertain-retry-btn"
                    (click)="retryUncertainSave()"
                  >
                    Retry account save
                  </button>
                </div>
              </div>
            }

            @if (validationDefects().length > 0) {
              <div class="mc-status-banner mc-status-error" role="alert">
                <mc-icon name="error" [size]="20" />
                <div>
                  <strong>Cannot apply keyboard map:</strong>
                  <ul class="mc-kbd-defect-list">
                    @for (defect of validationDefects(); track defect) {
                      <li>{{ defect }}</li>
                    }
                  </ul>
                </div>
              </div>
            }

            @if (appliedNotice(); as notice) {
              <div class="mc-status-banner mc-status-confirmed" role="status">
                <mc-icon name="confirmed" [size]="20" />
                <span>{{ notice }}</span>
              </div>
            }

            <!-- Persistence Scope (My account vs This browser) -->
            <fieldset class="mc-kbd-fieldset">
              <legend class="mc-field-label">Persistence Scope (Schema v2)</legend>
              <div class="mc-kbd-inline-actions">
                @if (authStore.isAuthenticated()) {
                  <label class="mc-kbd-radio-option">
                    <input
                      type="radio"
                      name="mcPrefScope"
                      value="ACCOUNT"
                      data-testid="pref-scope-account"
                      [ngModel]="targetScope()"
                      (ngModelChange)="targetScope.set($event)"
                    />
                    <span><strong>My account</strong> (syncs across browsers)</span>
                  </label>
                }
                <label class="mc-kbd-radio-option">
                  <input
                    type="radio"
                    name="mcPrefScope"
                    value="BROWSER"
                    data-testid="pref-scope-browser"
                    [ngModel]="targetScope()"
                    (ngModelChange)="targetScope.set($event)"
                  />
                  <span>
                    <strong>This browser</strong> ({{
                      uiPrefs.sessionOnlyMode() || !uiPrefs.storageAvailable()
                        ? 'Applies for this session only'
                        : 'Local browser override'
                    }})
                  </span>
                </label>

                @if (uiPrefs.maskedAccountFields().length > 0) {
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    data-testid="pref-use-account-in-browser-btn"
                    (click)="clearBrowserOverridesToInheritAccount()"
                  >
                    Use account settings in this browser
                  </button>
                }
              </div>
            </fieldset>

            <!-- Appearance & Text Scale (WCAG 2.2 AA Live Preview) -->
            <div class="mc-kbd-two-col">
              <fieldset class="mc-kbd-fieldset">
                <legend class="mc-field-label">Appearance Theme</legend>
                <div class="mc-kbd-inline-actions">
                  @for (modeOpt of appearanceModes; track modeOpt) {
                    <label class="mc-kbd-radio-option">
                      <input
                        type="radio"
                        name="mcAppearanceMode"
                        [value]="modeOpt"
                        [attr.data-testid]="'pref-appearance-' + modeOpt.toLowerCase()"
                        [ngModel]="pendingAppearance()"
                        (ngModelChange)="onAppearanceChange($event)"
                      />
                      <span>{{ formatAppearanceOption(modeOpt) }}</span>
                    </label>
                  }
                </div>
              </fieldset>

              <fieldset class="mc-kbd-fieldset">
                <legend class="mc-field-label">Text Size (75% – 200%)</legend>
                <div class="mc-kbd-inline-actions">
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    aria-label="Decrease text size"
                    data-testid="pref-scale-decrease-btn"
                    (click)="stepScale('DECREASE')"
                  >
                    &minus;
                  </button>
                  <select
                    class="mc-select mc-kbd-scale-select"
                    aria-label="Text size percentage"
                    data-testid="pref-text-scale-select"
                    [ngModel]="pendingTextScale()"
                    (ngModelChange)="onTextScaleChange($event)"
                  >
                    @for (sc of textScales; track sc) {
                      <option [ngValue]="sc">{{ sc }}%</option>
                    }
                  </select>
                  <button
                    type="button"
                    class="mc-btn mc-btn-secondary"
                    aria-label="Increase text size"
                    data-testid="pref-scale-increase-btn"
                    (click)="stepScale('INCREASE')"
                  >
                    +
                  </button>
                </div>
              </fieldset>
            </div>

            <fieldset class="mc-kbd-fieldset">
              <legend class="mc-field-label">Contextual Modal Action Dispatch Mode</legend>
              <div class="mc-kbd-radio-group">
                <label class="mc-kbd-radio-option">
                  <input
                    #initialModeRadio
                    type="radio"
                    name="mcKbdMode"
                    value="CHARACTER"
                    [ngModel]="pendingMode()"
                    (ngModelChange)="onModeChange($event)"
                  />
                  <div>
                    <strong>Immediate Action Letters (Default — en-GB)</strong>
                    <div class="mc-kbd-opt-desc">
                      Single declared ASCII pair (e.g. <code>[S]</code>, <code>[C]</code>,
                      <code>[P]</code>) when focus is outside text inputs.
                    </div>
                  </div>
                </label>

                <label class="mc-kbd-radio-option">
                  <input
                    type="radio"
                    name="mcKbdMode"
                    value="OFF"
                    [ngModel]="pendingMode()"
                    (ngModelChange)="onModeChange($event)"
                  />
                  <div>
                    <strong>Ordinary Keyboard Only (Disable Action Letters)</strong>
                    <div class="mc-kbd-opt-desc">
                      Suppresses all single-letter modal shortcuts; <code>Tab</code>,
                      <code>Shift+Tab</code>, <code>Enter</code>, <code>Space</code>, and
                      <code>Esc</code> remain active.
                    </div>
                  </div>
                </label>

                <label class="mc-kbd-radio-option">
                  <input
                    type="radio"
                    name="mcKbdMode"
                    value="MODIFIER"
                    [ngModel]="pendingMode()"
                    (ngModelChange)="onModeChange($event)"
                  />
                  <div>
                    <strong>Qualified Modifier Chords</strong>
                    <div class="mc-kbd-opt-desc">
                      Dispatches modal actions only via collision-free qualified modifier chords.
                    </div>
                  </div>
                </label>
              </div>
            </fieldset>

            <div class="mc-kbd-scanner-box">
              <label class="mc-kbd-checkbox-row">
                <input
                  type="checkbox"
                  name="scannerSuppression"
                  [ngModel]="shortcutRegistry.scannerWorkflowActive()"
                  (ngModelChange)="onToggleScannerSuppression($event)"
                />
                <span>
                  <strong>Unframed Barcode Scanner Workflow Active</strong> — Suppresses
                  character-only shortcuts globally across open and new dialogs without overwriting
                  saved mode.
                </span>
              </label>
            </div>

            @if (pendingMode() === 'MODIFIER') {
              <div class="mc-kbd-modifier-section">
                <div>
                  <label class="mc-field-label" for="mc-kbd-profile-select">
                    Qualified Environment Profile
                  </label>
                  <select
                    id="mc-kbd-profile-select"
                    name="pendingProfileId"
                    class="mc-select"
                    [ngModel]="pendingProfileId() ?? ''"
                    (ngModelChange)="onProfileChange($event)"
                  >
                    <option value="">-- Select Qualified Profile --</option>
                    @for (prof of availableProfiles(); track prof.profileId) {
                      <option [value]="prof.profileId">
                        {{ prof.label }} ({{ prof.environment.os }} /
                        {{ prof.environment.keyboardLayout }})
                      </option>
                    }
                  </select>
                </div>

                @if (selectedProfile(); as prof) {
                  <div class="mc-kbd-env-meta">
                    <span><strong>Browser:</strong> {{ prof.environment.browser }}</span>
                    <span><strong>Fn Mode:</strong> {{ prof.environment.functionKeyMode }}</span>
                    <span><strong>IME:</strong> {{ prof.environment.ime }}</span>
                    <span><strong>AT:</strong> {{ prof.environment.assistiveTech }}</span>
                  </div>

                  <div class="mc-kbd-override-scroll">
                    <table
                      class="mc-table mc-table-dense"
                      aria-label="Per-Context Modifier Chord Remapping"
                    >
                      <thead>
                        <tr>
                          <th>Context</th>
                          <th>Command</th>
                          <th>Default Chord</th>
                          <th>Qualified Chord Override</th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (b of prof.bindings; track b.contextId + ':' + b.commandId) {
                          <tr>
                            <td class="mc-code">{{ b.contextId }}</td>
                            <td class="mc-code">{{ b.commandId }}</td>
                            <td><span class="mc-kbd">{{ b.defaultChord }}</span></td>
                            <td>
                              <select
                                class="mc-select"
                                [attr.aria-label]="'Chord for ' + b.contextId + ' ' + b.commandId"
                                [ngModel]="overrideFor(b.contextId, b.commandId, b.defaultChord)"
                                (ngModelChange)="
                                  setOverride(b.contextId, b.commandId, b.defaultChord, $event)
                                "
                              >
                                @for (cand of b.qualifiedCandidates; track cand) {
                                  <option [value]="cand">{{ cand }}</option>
                                }
                              </select>
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  </div>
                }
              </div>
            }
          </div>

          <div class="mc-kbd-modal-footer">
            <button
              type="button"
              class="mc-btn mc-btn-primary"
              data-testid="kbd-apply-btn"
              (click)="applySettings()"
            >
              <span>Apply</span>
              @if (actionHint('keyboard.apply', 'A/a'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>

            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="kbd-defaults-btn"
              (click)="resetPendingToDefaults()"
            >
              <span>Reset defaults</span>
              @if (actionHint('keyboard.defaults', 'D/d'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>

            <button
              type="button"
              class="mc-btn mc-btn-secondary"
              data-testid="kbd-close-btn"
              (click)="closeWithoutApply()"
            >
              <mc-icon name="close" [size]="16" />
              <span>Close</span>
              @if (actionHint('editor.close', 'C/c'); as hint) {
                <span class="mc-action-key-badge" aria-hidden="true">[{{ hint }}]</span>
              }
            </button>
          </div>
        </div>
      </div>
    }
  `,
  styles: `
    .mc-kbd-modal-backdrop {
      position: fixed;
      inset: 0;
      background: rgba(15, 23, 42, 0.48);
      z-index: var(--mc-z-modal);
      display: flex;
      align-items: center;
      justify-content: center;
      padding: var(--mc-space-4);
    }

    .mc-kbd-modal-card {
      width: min(740px, 95vw);
      max-height: 88vh;
      display: flex;
      flex-direction: column;
      overflow: hidden;
    }

    .mc-kbd-modal-header {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
      padding: var(--mc-space-3) var(--mc-space-4);
      background: var(--mc-color-slate-900);
      color: #ffffff;

      div {
        display: flex;
        align-items: center;
        gap: var(--mc-space-2);
      }
    }

    .mc-kbd-scope-note {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-teal-100);
      font-weight: 600;
    }

    .mc-kbd-modal-body {
      padding: var(--mc-space-4);
      overflow-y: auto;
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-3);
    }

    .mc-kbd-banner-col {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-kbd-inline-actions {
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: var(--mc-space-2);
    }

    .mc-kbd-two-col {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: var(--mc-space-3);
    }

    @media (max-width: 720px) {
      .mc-kbd-two-col {
        grid-template-columns: 1fr;
      }
    }

    .mc-kbd-scale-select {
      width: auto;
      min-width: 110px;
    }

    .mc-kbd-defect-list {
      margin: 4px 0 0;
      padding-left: 1.1rem;
      font-size: var(--mc-font-size-xs);
    }

    .mc-kbd-fieldset {
      border: var(--mc-border-strong);
      border-radius: var(--mc-radius-sm);
      padding: var(--mc-space-3);
      margin: 0;
    }

    .mc-kbd-radio-group {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
      margin-top: var(--mc-space-1);
    }

    .mc-kbd-radio-option {
      display: inline-flex;
      align-items: flex-start;
      gap: var(--mc-space-2);
      cursor: pointer;
      font-size: var(--mc-font-size-sm);
    }

    .mc-kbd-opt-desc {
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-600);
      margin-top: 2px;
    }

    .mc-kbd-scanner-box {
      padding: var(--mc-space-2) var(--mc-space-3);
      background: var(--mc-color-mist-canvas);
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
    }

    .mc-kbd-checkbox-row {
      display: flex;
      align-items: flex-start;
      gap: var(--mc-space-2);
      font-size: var(--mc-font-size-xs);
      cursor: pointer;
    }

    .mc-kbd-modifier-section {
      display: flex;
      flex-direction: column;
      gap: var(--mc-space-2);
    }

    .mc-kbd-env-meta {
      display: flex;
      flex-wrap: wrap;
      gap: var(--mc-space-3);
      font-size: var(--mc-font-size-xs);
      color: var(--mc-color-slate-700);
      padding: 6px 8px;
      background: var(--mc-color-canvas);
      border-radius: var(--mc-radius-xs);
    }

    .mc-kbd-override-scroll {
      max-height: 210px;
      overflow-y: auto;
      border: var(--mc-border-subtle);
      border-radius: var(--mc-radius-xs);
    }

    .mc-kbd-modal-footer {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: var(--mc-space-2);
      padding: var(--mc-space-3) var(--mc-space-4);
      border-top: var(--mc-border-subtle);
      background: var(--mc-color-canvas);
    }
  `,
})
export class KeyboardSettingsModalComponent {
  readonly shortcutRegistry = inject(ShortcutCommandRegistry);
  readonly uiPrefs = inject(UiPreferencesService);
  readonly authStore = inject(AuthStore);
  private readonly destroyRef = inject(DestroyRef);

  private readonly initialModeRadio = viewChild<ElementRef<HTMLInputElement>>('initialModeRadio');

  readonly appearanceModes = ALLOWED_APPEARANCE_MODES;
  readonly textScales = ALLOWED_TEXT_SCALES;

  readonly targetScope = signal<PreferencePersistenceTarget>('BROWSER');
  readonly pendingAppearance = signal<AppearanceMode>(DEFAULT_UI_PREFERENCES_V2.appearance);
  readonly pendingTextScale = signal<TextScalePercent>(DEFAULT_UI_PREFERENCES_V2.textScalePercent);

  readonly pendingMode = signal<KeyboardDispatchMode>('CHARACTER');
  readonly pendingLocale = signal<string>('en-GB');
  readonly pendingProfileId = signal<string | null>(null);
  readonly pendingOverrides = signal<readonly KeyboardOverrideEntry[]>([]);
  readonly validationDefects = signal<readonly string[]>([]);
  readonly appliedNotice = signal<string | null>(null);

  private activeModalHandle: ActiveModalContextHandle | null = null;
  private invokingElement: HTMLElement | null = null;

  constructor() {
    effect(() => {
      const open = this.shortcutRegistry.settingsModalOpen();
      untracked(() => {
        if (open) {
          this.onOpen();
        } else {
          this.releaseModalContext();
        }
      });
    });

    this.destroyRef.onDestroy(() => {
      this.releaseModalContext();
    });
  }

  formatAppearanceOption(mode: AppearanceMode): string {
    switch (mode) {
      case 'LIGHT':
        return 'Light';
      case 'DARK':
        return 'Dark';
      case 'SYSTEM':
        return 'System';
    }
  }

  availableProfiles(): readonly QualifiedModifierProfile[] {
    return this.shortcutRegistry.listQualifiedModifierProfiles();
  }

  selectedProfile(): QualifiedModifierProfile | null {
    const id = this.pendingProfileId();
    if (!id) {
      return null;
    }
    return this.availableProfiles().find((p) => p.profileId === id) ?? null;
  }

  actionHint(commandId: string, pairSpec: 'A/a' | 'D/d' | 'C/c'): string | null {
    return this.shortcutRegistry.effectiveActionHint(
      'keyboard.settings',
      commandId,
      asciiPair(pairSpec),
    );
  }

  onAppearanceChange(mode: AppearanceMode): void {
    this.pendingAppearance.set(mode);
    this.appliedNotice.set(null);
    this.uiPrefs.setPreview({
      appearance: mode,
      textScalePercent: this.pendingTextScale(),
    });
  }

  onTextScaleChange(scale: TextScalePercent | string | number): void {
    const num = Number(scale) as TextScalePercent;
    if ((ALLOWED_TEXT_SCALES as readonly number[]).includes(num)) {
      this.pendingTextScale.set(num);
      this.appliedNotice.set(null);
      this.uiPrefs.setPreview({
        appearance: this.pendingAppearance(),
        textScalePercent: num,
      });
    }
  }

  stepScale(direction: 'INCREASE' | 'DECREASE'): void {
    const currentIdx = ALLOWED_TEXT_SCALES.indexOf(this.pendingTextScale());
    const safeIdx = currentIdx >= 0 ? currentIdx : ALLOWED_TEXT_SCALES.indexOf(100);
    const nextIdx =
      direction === 'INCREASE'
        ? Math.min(ALLOWED_TEXT_SCALES.length - 1, safeIdx + 1)
        : Math.max(0, safeIdx - 1);
    const nextScale = ALLOWED_TEXT_SCALES[nextIdx];
    this.onTextScaleChange(nextScale);
  }

  clearBrowserOverridesToInheritAccount(): void {
    this.uiPrefs.useAccountSettingsInThisBrowser();
    this.pendingAppearance.set(this.uiPrefs.effectiveAppearance());
    this.pendingTextScale.set(this.uiPrefs.effectiveTextScalePercent());
    this.appliedNotice.set('Browser overrides cleared — inheriting confirmed account settings.');
  }

  async reloadFromConflict(): Promise<void> {
    await this.uiPrefs.resolveConflictReloadServer();
    this.pendingAppearance.set(this.uiPrefs.effectiveAppearance());
    this.pendingTextScale.set(this.uiPrefs.effectiveTextScalePercent());
  }

  async applyOverConflict(): Promise<void> {
    await this.uiPrefs.resolveConflictApplySelected({
      appearance: this.pendingAppearance(),
      textScalePercent: this.pendingTextScale(),
      keyboard: this.buildPendingKeyboardState(),
    });
  }

  async retryUncertainSave(): Promise<void> {
    await this.uiPrefs.retryUncertainAccountSave();
  }

  onModeChange(mode: KeyboardDispatchMode): void {
    this.pendingMode.set(mode);
    this.appliedNotice.set(null);
    if (mode === 'MODIFIER' && !this.pendingProfileId()) {
      this.pendingProfileId.set(DEFAULT_QUALIFIED_MODIFIER_PROFILE_ID);
    }
  }

  onProfileChange(profileId: string): void {
    this.pendingProfileId.set(profileId || null);
    this.pendingOverrides.set([]);
    this.validationDefects.set([]);
    this.appliedNotice.set(null);
  }

  onToggleScannerSuppression(active: boolean): void {
    this.shortcutRegistry.setScannerWorkflowActive(Boolean(active));
  }

  overrideFor(contextId: string, commandId: string, defaultChord: string): string {
    const match = this.pendingOverrides().find(
      (o) => o.contextId === contextId && o.commandId === commandId,
    );
    return match ? match.chordId : defaultChord;
  }

  setOverride(
    contextId: string,
    commandId: string,
    defaultChord: string,
    selectedChord: string,
  ): void {
    const withoutCurrent = this.pendingOverrides().filter(
      (o) => !(o.contextId === contextId && o.commandId === commandId),
    );
    if (!selectedChord || selectedChord === defaultChord) {
      this.pendingOverrides.set(withoutCurrent);
    } else {
      this.pendingOverrides.set([
        ...withoutCurrent,
        { contextId, commandId, chordId: selectedChord },
      ]);
    }
    this.validationDefects.set([]);
    this.appliedNotice.set(null);
  }

  /**
   * `Doc 45 §2 & §5` + `Doc 50`: Reset defaults changes ONLY the pending preference form; Apply is still required.
   */
  resetPendingToDefaults(): void {
    this.pendingAppearance.set(DEFAULT_UI_PREFERENCES_V2.appearance);
    this.pendingTextScale.set(DEFAULT_UI_PREFERENCES_V2.textScalePercent);
    this.pendingMode.set(DEFAULT_KEYBOARD_PREFERENCES.mode);
    this.pendingLocale.set(DEFAULT_KEYBOARD_PREFERENCES.locale);
    this.pendingProfileId.set(DEFAULT_KEYBOARD_PREFERENCES.profileId);
    this.pendingOverrides.set([]);
    this.validationDefects.set([]);
    this.uiPrefs.setPreview({
      appearance: DEFAULT_UI_PREFERENCES_V2.appearance,
      textScalePercent: DEFAULT_UI_PREFERENCES_V2.textScalePercent,
    });
    this.appliedNotice.set('Pending form reset to defaults (press Apply to commit).');
  }

  private buildPendingKeyboardState(): KeyboardPreferenceState {
    return {
      version: 1,
      mode: this.pendingMode(),
      locale: this.pendingLocale(),
      profileId: this.pendingMode() === 'MODIFIER' ? this.pendingProfileId() : null,
      overrides: this.pendingMode() === 'MODIFIER' ? this.pendingOverrides() : [],
    };
  }

  applySettings(): void {
    const kbdState = this.buildPendingKeyboardState();
    const result = this.shortcutRegistry.applyKeyboardPreferences(kbdState);

    if (!result.valid) {
      this.validationDefects.set(result.defects);
      this.appliedNotice.set(null);
      return;
    }

    this.validationDefects.set([]);
    if (this.targetScope() === 'ACCOUNT' && this.authStore.isAuthenticated()) {
      void this.uiPrefs.saveAccountPreferences({
        appearance: this.pendingAppearance(),
        textScalePercent: this.pendingTextScale(),
        keyboard: kbdState,
      });
      this.appliedNotice.set('Preferences saved to account.');
    } else {
      this.uiPrefs.setBrowserLocalOverride({
        appearance: this.pendingAppearance(),
        textScalePercent: this.pendingTextScale(),
        keyboard: kbdState,
      });
      this.appliedNotice.set('Preferences applied to this browser.');
    }
    this.closeModalAndRestoreFocus();
  }

  closeWithoutApply(event?: Event): void {
    event?.preventDefault();
    event?.stopPropagation();
    this.uiPrefs.revertPreview();
    this.closeModalAndRestoreFocus();
  }

  private onOpen(): void {
    if (typeof document !== 'undefined' && document.activeElement instanceof HTMLElement) {
      this.invokingElement = document.activeElement;
    }

    this.targetScope.set(this.authStore.isAuthenticated() ? 'ACCOUNT' : 'BROWSER');
    this.pendingAppearance.set(this.uiPrefs.effectiveAppearance());
    this.pendingTextScale.set(this.uiPrefs.effectiveTextScalePercent());

    const current = this.shortcutRegistry.preferences();
    this.pendingMode.set(current.mode);
    this.pendingLocale.set(current.locale);
    this.pendingProfileId.set(current.profileId);
    this.pendingOverrides.set([...current.overrides]);
    this.validationDefects.set([]);
    this.appliedNotice.set(null);

    this.releaseModalContext();
    const actions: readonly ModalActionDefinition[] = [
      {
        contextId: 'keyboard.settings',
        locale: 'en-GB',
        commandId: 'keyboard.apply',
        label: 'Apply',
        letterPair: asciiPair('A/a'),
        permissionRef: 'public',
        effect: 'Validate complete selected preference map and apply atomically.',
        normalActivation: () => this.applySettings(),
        iconSemanticId: null,
        stateTokenRole: 'action.primary',
      },
      {
        contextId: 'keyboard.settings',
        locale: 'en-GB',
        commandId: 'keyboard.defaults',
        label: 'Reset defaults',
        letterPair: asciiPair('D/d'),
        permissionRef: 'public',
        effect: 'Reset pending preference form to Light, 100%, CHARACTER, en-GB, null profile.',
        normalActivation: () => this.resetPendingToDefaults(),
        iconSemanticId: null,
        stateTokenRole: 'action.secondary',
      },
      {
        contextId: 'keyboard.settings',
        locale: 'en-GB',
        commandId: 'editor.close',
        label: 'Close',
        letterPair: asciiPair('C/c'),
        permissionRef: 'public',
        effect: 'Discard unapplied preference edits and restore invoking control.',
        normalActivation: () => this.closeWithoutApply(),
        iconSemanticId: 'mc.icon.close',
        stateTokenRole: 'action.secondary',
      },
    ];

    this.activeModalHandle = this.shortcutRegistry.openModalContext(actions, 'en-GB');

    queueMicrotask(() => {
      this.initialModeRadio()?.nativeElement.focus();
    });
  }

  private closeModalAndRestoreFocus(): void {
    this.releaseModalContext();
    this.shortcutRegistry.closeKeyboardSettingsModal();
    const target = this.invokingElement;
    this.invokingElement = null;
    if (target && typeof target.focus === 'function') {
      queueMicrotask(() => target.focus());
    }
  }

  private releaseModalContext(): void {
    if (this.activeModalHandle) {
      this.activeModalHandle.close();
      this.activeModalHandle = null;
    }
  }
}
