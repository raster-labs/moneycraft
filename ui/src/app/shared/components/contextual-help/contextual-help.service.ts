import { DOCUMENT } from '@angular/common';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';

/**
 * MoneyCraft Host Iteration `v1.1.0` — Local Contextual Help Service (`Docs 50 §7 & 57`).
 *
 * Serves all 26 contextual help entries (`HELP-110-001..025` + `HELP-110-026` keyboard reference)
 * 100% locally for private-LAN operation (zero external CDNs, trackers, or accounting data egress),
 * manages hover/focus supplementary tooltips (`WCAG 2.2 1.4.13`) and the explicit Help Panel,
 * and intercepts `Escape` on `window` capture BEFORE `ShortcutCommandRegistry` or any financial modal!
 */

export interface ContextualHelpEntry {
  readonly help_id: string;
  readonly locale: string;
  readonly content_revision: string;
  readonly title: string;
  readonly context_key: string;
  readonly short_text: string;
  readonly instructions: string;
  readonly audience: string;
  readonly content_status: string;
  readonly implementation_status: string;
}

export interface ContextualHelpCatalogue {
  readonly version: string;
  readonly locale: string;
  readonly delivery_mode?: string;
  readonly coverage: string;
  readonly items: readonly ContextualHelpEntry[];
}

export interface ActiveTooltipSession {
  readonly tooltipDomId: string;
  readonly helpId: string;
  readonly title: string;
  readonly shortText: string;
  readonly dismissOnEscape: () => void;
}

export const BUNDLED_CONTEXTUAL_HELP_ITEMS_EN_GB: readonly ContextualHelpEntry[] = Object.freeze([
  {
    help_id: 'HELP-110-001',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Appearance settings',
    context_key: 'appearance',
    short_text:
      'Choose Light, Dark or System. System follows the appearance preference reported by your operating system or browser.',
    instructions:
      'Choose a mode. Preview it, then save to My account or This browser. A local browser override takes priority over your account choice.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-002',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Text size',
    context_key: 'textScalePercent',
    short_text: 'Increase or decrease MoneyCraft text size without changing your financial data.',
    instructions:
      'Use the size controls or select a percentage. Reset returns to 100%. Browser zoom remains available independently.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-003',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Preference scope',
    context_key: 'scope',
    short_text:
      'My account follows you when you sign in. This browser affects this browser profile only.',
    instructions:
      'Select the scope before saving. Use account settings in this browser removes the selected local override. It does not reset your account.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-004',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Settings conflict',
    context_key: 'conflict',
    short_text:
      'These settings changed in another session. Review the saved values before applying your changes.',
    instructions:
      'Reload current settings, compare the changed fields and apply your intended values deliberately. Unrelated newer values are retained.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-005',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Temporary settings',
    context_key: 'storageUnavailable',
    short_text: 'These settings apply for this session only because local storage is unavailable.',
    instructions:
      'You can continue using the controls. Sign in and save to your account if available, or use this temporary setting until the session ends.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-006',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Keyboard settings',
    context_key: 'keyboard',
    short_text:
      'Choose character actions, a qualified modifier profile, or ordinary keyboard controls only.',
    instructions:
      'Letters typed in entry fields must remain text. Saved shortcuts may be unavailable on a different keyboard or OS; ordinary Tab and button controls remain available.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-007',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Company selection',
    context_key: 'company',
    short_text: 'Choose the company you are authorised to work with.',
    instructions:
      'Select a company, then Open. Switching company does not post a draft; resolve any pending or uncertain operation before switching.',
    audience: 'Company reader',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-008',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Ledger selection',
    context_key: 'ledger',
    short_text: 'Select a ledger in the current company.',
    instructions:
      'Search the authorised list. Select fills this line only. Create ledger is available only with permission. Close returns without changing the selected value.',
    audience: 'Authorised entry user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-009',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Accounting date',
    context_key: 'accountingDate',
    short_text: 'This is the business date of the entry, within an allowed accounting period.',
    instructions:
      'Choose the intended date and period. Applying a date selection does not save, approve or post the journal.',
    audience: 'Authorised entry user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-010',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Journal amounts',
    context_key: 'amounts',
    short_text: 'Enter debit and credit amounts at the company currency precision.',
    instructions:
      'Use valid ledgers. Check that total debit equals total credit before saving or posting. Display scaling does not change stored amounts.',
    audience: 'Authorised entry user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-011',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Save draft',
    context_key: 'saveDraft',
    short_text: 'Save keeps an editable draft; it does not approve or post it.',
    instructions:
      'Correct any highlighted fields. Wait for confirmation before leaving. If the outcome is unknown, use Check status instead of starting another save.',
    audience: 'Voucher creator/editor',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-012',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Approval',
    context_key: 'approve',
    short_text: 'Approval records the required review of the current saved version.',
    instructions:
      'Review the entry and total. Availability depends on your permission and the company approval policy. Changes can require fresh approval.',
    audience: 'Voucher approver',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-013',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Posting',
    context_key: 'post',
    short_text: 'Posting commits the admitted journal to the accounts.',
    instructions:
      'Review the company, date, period, version and total. Confirm once. A network interruption does not prove that posting failed.',
    audience: 'Voucher poster',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-014',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Uncertain outcome',
    context_key: 'requestUncertain',
    short_text: 'The server outcome has not yet been confirmed. Do not create a duplicate operation.',
    instructions:
      'Use Check status. Retry original only when offered; it retains the same request identity. An unavailable result is not proof that nothing committed.',
    audience: 'Authorised entry user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-015',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Concurrent edit',
    context_key: 'staleVersion',
    short_text: 'This record changed after you opened it.',
    instructions:
      'Read the current version and compare your intended changes. Do not submit an old or redacted record as a blind replacement.',
    audience: 'Authorised editor',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-016',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Report filters',
    context_key: 'reportFilters',
    short_text: 'Choose the report dates explicitly.',
    instructions:
      'Apply requests the report for those bounds. Clear restores the selected period in the form; it does not run an unlimited report.',
    audience: 'Report reader',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-017',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Report output',
    context_key: 'reportOutput',
    short_text: 'Print or export the authorised complete PDF for the selected report bounds.',
    instructions:
      'Confirm the report and dates. Output requires the appropriate export permission. Printer acceptance may not prove that paper was printed.',
    audience: 'Report exporter',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-018',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Government integration profile',
    context_key: 'governmentProfile',
    short_text:
      'A profile binds one provider, environment and permitted company registration to approved operations.',
    instructions:
      'Only Super Admin can view or change this configuration. Sandbox and production have separate bindings and credentials.',
    audience: 'Super Admin',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-019',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Replace credentials',
    context_key: 'credentialReplace',
    short_text: 'Enter replacement credentials. Existing stored secrets cannot be revealed.',
    instructions:
      'Stage the new version, validate it using an approved safe check, then apply it deliberately. Never paste credentials into help, logs or support messages.',
    audience: 'Super Admin',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-020',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Validate connection',
    context_key: 'validateConnection',
    short_text:
      'Validation checks the exact staged configuration; it does not file a return or generate an invoice.',
    instructions:
      'Review the declared safe probe, including any OTP or charge. A successful check applies only to the tested provider, environment and revision.',
    audience: 'Super Admin',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-021',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Apply integration',
    context_key: 'activateIntegration',
    short_text:
      'Apply switches new work to the validated configuration after fresh authentication.',
    instructions:
      'Confirm provider, company registration and environment. Already submitted work retains its original profile for reconciliation.',
    audience: 'Super Admin',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-022',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Filing status',
    context_key: 'filingStatus',
    short_text: 'Uploaded, accepted, verified and filed are different provider states.',
    instructions:
      'Read the recorded provider acknowledgement and status. Do not assume that uploading a file completed filing, or resubmit after an unknown response.',
    audience: 'Authorised statutory-workflow user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-023',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Desktop sign-in',
    context_key: 'nativeSignIn',
    short_text: 'Sign in through the approved browser page for your MoneyCraft server.',
    instructions:
      'The desktop application does not save your password. Passkeys and password-manager options depend on the qualified browser and account policy.',
    audience: 'Native client user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-024',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Certificate failure',
    context_key: 'certificateFailure',
    short_text:
      'The server identity could not be verified. No credentials should be sent to this connection.',
    instructions:
      'Check the configured server address. Ask the authorised administrator to correct the certificate or provision the approved trust profile; do not bypass the warning.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-025',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Local biometric unlock',
    context_key: 'biometric',
    short_text: 'A local biometric check can unlock protected device storage.',
    instructions:
      'It does not restore expired server access or replace a server request to sign in again. Use the approved browser sign-in when required.',
    audience: 'Native client user',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
  {
    help_id: 'HELP-110-026',
    locale: 'en-GB',
    content_revision: '1.1.0',
    title: 'Keyboard shortcuts and function bar reference',
    context_key: 'keyboardShortcuts',
    short_text:
      'Plain keys invoke direct function actions; single underline denotes Alt+Key; double underline denotes Ctrl+Key.',
    instructions:
      'Function keys: F1 Help, F2 Working Date (Alt+F2 Period), F3 Select Company (Ctrl+F3 Shut Company), F4 Contra, F5 Payment (Alt+F5 Debit Note), F6 Receipt (Alt+F6 Credit Note), F7 Journal (Alt+F7 Stock Journal, Ctrl+F7 Physical Stock), F8 Sales (Alt+F8 Delivery Note, Ctrl+F8 Sales Order), F9 Purchase (Alt+F9 Receipt Note, Ctrl+F9 Purchase Order), F10 Other Vouchers, F11 Company Features, F12 Configure. Navigation and editing: Alt+G Go To, Ctrl+G Switch To, Alt+K/Y/Z/O/E/P Top Bar menus, Ctrl+H Change Mode, Ctrl+L Optional, Ctrl+I Tax Analysis, Alt+C Inline Create / Column / Calculator, Ctrl+R Repeat Narration, PageUp/PageDown Previous/Next Voucher, Ctrl+A Accept, Alt+2 Duplicate, Alt+I Insert, Alt+X Cancel, Alt+R/Alt+U Hide/Restore Line, Space/Ctrl+Space Select Rows, Shift+Enter Explode Details, and Escape Dismiss Topmost Help or Step Back.',
    audience: 'All users',
    content_status: 'AUTHORED_FOR_ITERATION',
    implementation_status: 'BOUND_TO_UI_V1_1_0',
  },
]);

const CONTEXT_KEY_ALIASES: Readonly<Record<string, string>> = Object.freeze({
  shortcuts: 'HELP-110-026',
  tallyShortcuts: 'HELP-110-026',
  f1: 'HELP-110-026',
  f1Help: 'HELP-110-026',
  textSize: 'HELP-110-002',
  theme: 'HELP-110-001',
});

@Injectable({ providedIn: 'root' })
export class ContextualHelpService {
  private readonly document = inject(DOCUMENT);
  private readonly destroyRef = inject(DestroyRef);

  private readonly entriesSignal = signal<readonly ContextualHelpEntry[]>(
    BUNDLED_CONTEXTUAL_HELP_ITEMS_EN_GB,
  );
  private readonly activeTooltipSignal = signal<ActiveTooltipSession | null>(null);
  private readonly panelOpenSignal = signal<boolean>(false);
  private readonly activePanelEntrySignal = signal<ContextualHelpEntry | null>(null);
  private readonly returnControlNameSignal = signal<string>('previous control');
  private readonly browseSearchQuerySignal = signal<string>('');
  private readonly showTopicDirectorySignal = signal<boolean>(false);

  private invokingElement: HTMLElement | null = null;
  private overlayOrder: ('TOOLTIP' | 'PANEL')[] = [];

  readonly entries = this.entriesSignal.asReadonly();
  readonly activeTooltip = this.activeTooltipSignal.asReadonly();
  readonly panelOpen = this.panelOpenSignal.asReadonly();
  readonly activePanelEntry = this.activePanelEntrySignal.asReadonly();
  readonly returnControlName = this.returnControlNameSignal.asReadonly();
  readonly browseSearchQuery = this.browseSearchQuerySignal.asReadonly();
  readonly showTopicDirectory = this.showTopicDirectorySignal.asReadonly();

  readonly closeButtonLabel = computed<string>(
    () => `Close help and return to ${this.returnControlNameSignal()}.`,
  );

  readonly filteredDirectoryEntries = computed<readonly ContextualHelpEntry[]>(() => {
    const q = this.browseSearchQuerySignal().trim().toLowerCase();
    const all = this.entriesSignal();
    if (!q) {
      return all;
    }
    return all.filter(
      (item) =>
        item.help_id.toLowerCase().includes(q) ||
        item.title.toLowerCase().includes(q) ||
        item.context_key.toLowerCase().includes(q) ||
        item.short_text.toLowerCase().includes(q) ||
        item.instructions.toLowerCase().includes(q),
    );
  });

  constructor() {
    const onEscapeCapture = (event: KeyboardEvent): void => {
      if (event.key !== 'Escape') {
        return;
      }
      if (!this.hasOpenHelpOverlay()) {
        return;
      }

      // `Doc 50 §7` (`[WEB-S13]`): Escape dismisses the topmost help overlay BEFORE any underlying
      // financial dialog or `ShortcutCommandRegistry` can process that keystroke!
      event.preventDefault();
      event.stopPropagation();
      event.stopImmediatePropagation();
      this.dismissTopmostOverlay();
    };

    const win = this.document.defaultView;
    if (win) {
      win.addEventListener('keydown', onEscapeCapture, { capture: true });
      this.destroyRef.onDestroy(() =>
        win.removeEventListener('keydown', onEscapeCapture, { capture: true }),
      );
    } else {
      this.document.addEventListener('keydown', onEscapeCapture, { capture: true });
      this.destroyRef.onDestroy(() =>
        this.document.removeEventListener('keydown', onEscapeCapture, { capture: true }),
      );
    }
  }

  hasOpenHelpOverlay(): boolean {
    return this.activeTooltipSignal() !== null || this.panelOpenSignal();
  }

  listEntries(): readonly ContextualHelpEntry[] {
    return this.entriesSignal();
  }

  getByHelpId(helpId: string): ContextualHelpEntry | null {
    const normalized = helpId.trim().toUpperCase();
    return this.entriesSignal().find((e) => e.help_id.toUpperCase() === normalized) ?? null;
  }

  getByContextKey(contextKey: string): ContextualHelpEntry | null {
    const trimmed = contextKey.trim();
    const aliasHelpId = CONTEXT_KEY_ALIASES[trimmed];
    if (aliasHelpId) {
      return this.getByHelpId(aliasHelpId);
    }
    return (
      this.entriesSignal().find(
        (e) => e.context_key.toLowerCase() === trimmed.toLowerCase(),
      ) ?? null
    );
  }

  resolveEntry(helpIdOrContextKey: string): ContextualHelpEntry | null {
    if (!helpIdOrContextKey || helpIdOrContextKey.trim().length === 0) {
      return null;
    }
    return this.getByHelpId(helpIdOrContextKey) ?? this.getByContextKey(helpIdOrContextKey);
  }

  /**
   * Optional refresh from local static asset (`help/contextual-help.en-GB.json`).
   * Retains bundled local catalogue immediately if offline or running in unit tests.
   */
  async loadLocalCatalogue(locale = 'en-GB'): Promise<readonly ContextualHelpEntry[]> {
    if (locale !== 'en-GB' || typeof fetch !== 'function') {
      return this.entriesSignal();
    }
    for (const candidateUrl of [
      'help/contextual-help.en-GB.json',
      'assets/help/contextual-help.en-GB.json',
    ]) {
      try {
        const resp = await fetch(candidateUrl, {
          cache: 'no-store',
          credentials: 'same-origin',
        });
        if (!resp.ok) {
          continue;
        }
        const parsed = (await resp.json()) as Partial<ContextualHelpCatalogue>;
        if (Array.isArray(parsed.items) && parsed.items.length >= 25) {
          this.entriesSignal.set(parsed.items);
          return parsed.items;
        }
      } catch {
        // Offline / jsdom fallback uses BUNDLED_CONTEXTUAL_HELP_ITEMS_EN_GB seamlessly
      }
    }
    return this.entriesSignal();
  }

  registerActiveTooltip(session: ActiveTooltipSession): void {
    this.activeTooltipSignal.set(session);
    this.overlayOrder = [...this.overlayOrder.filter((k) => k !== 'TOOLTIP'), 'TOOLTIP'];
  }

  clearActiveTooltip(tooltipDomId: string): void {
    if (this.activeTooltipSignal()?.tooltipDomId === tooltipDomId) {
      this.activeTooltipSignal.set(null);
      this.overlayOrder = this.overlayOrder.filter((k) => k !== 'TOOLTIP');
    }
  }

  openHelpPanel(
    helpIdOrContextKey: string,
    options?: {
      readonly triggerElement?: HTMLElement | null;
      readonly controlName?: string | null;
      readonly showDirectory?: boolean;
    },
  ): boolean {
    const entry =
      this.resolveEntry(helpIdOrContextKey) ?? this.getByHelpId('HELP-110-026');
    if (!entry) {
      return false;
    }

    const activeEl =
      options?.triggerElement ??
      (this.document.activeElement instanceof HTMLElement ? this.document.activeElement : null);
    if (activeEl && activeEl !== this.document.body) {
      this.invokingElement = activeEl;
    }

    const resolvedControlName =
      options?.controlName && options.controlName.trim().length > 0
        ? options.controlName.trim()
        : entry.title.toLowerCase();

    this.activePanelEntrySignal.set(entry);
    this.returnControlNameSignal.set(resolvedControlName);
    this.showTopicDirectorySignal.set(Boolean(options?.showDirectory));
    this.browseSearchQuerySignal.set('');
    this.panelOpenSignal.set(true);
    this.overlayOrder = [...this.overlayOrder.filter((k) => k !== 'PANEL'), 'PANEL'];
    return true;
  }

  openGlobalShortcutHelp(triggerElement?: HTMLElement | null): boolean {
    return this.openHelpPanel('HELP-110-026', {
      triggerElement: triggerElement ?? null,
      controlName: 'Help button',
      showDirectory: true,
    });
  }

  selectDirectoryTopic(helpId: string): void {
    const entry = this.resolveEntry(helpId);
    if (entry) {
      this.activePanelEntrySignal.set(entry);
    }
  }

  setBrowseSearchQuery(query: string): void {
    this.browseSearchQuerySignal.set(query);
  }

  toggleTopicDirectory(): void {
    this.showTopicDirectorySignal.set(!this.showTopicDirectorySignal());
  }

  closeHelpPanel(restoreFocus = true): void {
    if (!this.panelOpenSignal()) {
      return;
    }
    this.panelOpenSignal.set(false);
    this.overlayOrder = this.overlayOrder.filter((k) => k !== 'PANEL');

    const target = this.invokingElement;
    this.invokingElement = null;
    if (restoreFocus && target && this.document.contains(target) && typeof target.focus === 'function') {
      queueMicrotask(() => {
        target.focus({ preventScroll: true });
      });
    }
  }

  dismissTopmostOverlay(): boolean {
    const top =
      this.overlayOrder.length > 0
        ? this.overlayOrder[this.overlayOrder.length - 1]
        : this.activeTooltipSignal()
          ? 'TOOLTIP'
          : this.panelOpenSignal()
            ? 'PANEL'
            : null;

    if (top === 'TOOLTIP' && this.activeTooltipSignal()) {
      const tip = this.activeTooltipSignal();
      tip?.dismissOnEscape();
      return true;
    }
    if (top === 'PANEL' && this.panelOpenSignal()) {
      this.closeHelpPanel(true);
      return true;
    }
    if (this.activeTooltipSignal()) {
      this.activeTooltipSignal()?.dismissOnEscape();
      return true;
    }
    if (this.panelOpenSignal()) {
      this.closeHelpPanel(true);
      return true;
    }
    return false;
  }
}
